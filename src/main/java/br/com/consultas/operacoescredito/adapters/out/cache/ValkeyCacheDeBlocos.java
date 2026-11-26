package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.observabilidade.CacheDaRequisicao;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.VersaoDosBlocos;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cache versionado no Valkey (ADR-0018, ADR-0019):
 * <ul>
 *     <li>blocos: um hash por operação e custódia no {@link FormatoDoHash formato} da regra de versão dos blocos.
 *     Com versão única, {@code op:{id_operacao}:{custodia}} com {@code versao} (epoch ms), um campo por bloco
 *     ({@code texto_detalhe} GZIP como veio do DynamoDB) e, se invalidado, {@code marcador}. Com versão por bloco,
 *     {@code opb:{id_operacao}:{custodia}} com {@code {bloco}}, {@code v:{bloco}} e, se invalidado,
 *     {@code m:{bloco}}. TTL {@code ttl};</li>
 *     <li>custódia: hash {@code custodia-vc:{id_operacao}} com {@code custodia}, {@code versao} e, se invalidado,
 *     {@code marcador}; TTL {@code ttl-custodia}. A chave compartilhada {@code custodia:{id_operacao}} (ADR-0017)
 *     continua sendo gravada como antes, mas não é lida.</li>
 * </ul>
 * Leitura, na requisição: um {@code HMGET} por hash, todos em pipeline e esperados juntos, no máximo pelo timeout
 * de comando ({@code spring.data.redis.timeout}). Um {@code HMGET} é atômico: com versão única, os blocos lidos de
 * um hash são sempre da mesma versão. Não lê com menos de {@code prazo-minimo-restante} do prazo da requisição, nem com mais de
 * {@code max-chaves-por-consulta} blocos (operações × blocos).
 * <p>
 * Gravação, fora da requisição (virtual thread, no máximo {@code max-gravacoes-simultaneas}): o script do formato
 * ({@link ScriptValkey#GRAVAR_VERSIONADO} ou {@link ScriptValkey#GRAVAR_POR_BLOCO}) por hash, em rodadas de até {@code chaves-por-comando} comandos em pipeline,
 * cada rodada dentro do timeout de comando. A resposta da consulta não espera a gravação.
 * <p>
 * Fail-open: toda leitura e cada gravação passam pelo circuit breaker {@value CacheConfig#CIRCUIT_BREAKER}; falha ou
 * circuito aberto viram "nada em cache" e a consulta segue no DynamoDB. Cada leitura e gravação é uma observação
 * {@code consultas.dependencia} ({@code dependencia=valkey}, {@code operacao=HMGET|EVALSHA|DEL}); cada bloco
 * procurado conta no contador {@code consultas.cache} ({@code bloco},
 * {@code resultado=hit|miss|indisponivel|ignorado}) e na contagem da requisição ({@link CacheDaRequisicao}).
 */
class ValkeyCacheDeBlocos implements CacheConfig.CacheDeBlocosECustodia {

    static final String DEPENDENCIA = "valkey";
    static final String METRICA_CACHE = "consultas.cache";
    static final String CAMPO_VERSAO = "versao";
    static final String CAMPO_MARCADOR = "marcador";
    static final String CAMPO_CUSTODIA = "custodia";

    private static final Logger log = LoggerFactory.getLogger(ValkeyCacheDeBlocos.class);
    /** Campos do hash de custódia, na ordem do {@code HMGET}. */
    private static final byte[][] CAMPOS_DA_CUSTODIA = {bytes(CAMPO_CUSTODIA), bytes(CAMPO_VERSAO),
            bytes(CAMPO_MARCADOR)};

    /** Valor da tag {@code resultado} de {@code consultas.cache}. */
    enum Resultado {
        /** Bloco encontrado no cache, na versão esperada. */
        HIT,
        /** Bloco procurado e ausente no cache (ou em outra versão, ou com o marcador de invalidação). */
        MISS,
        /** Cache não respondeu: Valkey fora, lento ou circuit breaker aberto. */
        INDISPONIVEL,
        /** Cache não consultado de propósito: blocos demais na consulta ou pouco prazo restante. */
        IGNORADO;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ComandosValkey comandos;
    private final FormatoDoHash formato;
    private final CircuitBreaker circuitBreaker;
    private final ObservationRegistry observationRegistry;
    private final Duration timeoutComando;
    private final byte[] ttlBlocos;
    private final byte[] ttlCustodia;
    private final long ttlCustodiaEmMilissegundos;
    private final int chavesPorComando;
    private final int maxChavesPorConsulta;
    private final Duration prazoMinimoRestante;
    private final Semaphore vagasDeGravacao;
    private final Executor gravacoes;
    private final Map<Resultado, Map<Bloco, Counter>> contadores = new EnumMap<>(Resultado.class);

    ValkeyCacheDeBlocos(ComandosValkey comandos, VersaoDosBlocos versaoDosBlocos, CircuitBreaker circuitBreaker,
                        ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                        CacheProperties properties, Duration timeoutComando, Executor gravacoes) {
        this.comandos = comandos;
        this.formato = FormatoDoHash.de(versaoDosBlocos);
        this.circuitBreaker = circuitBreaker;
        this.observationRegistry = observationRegistry;
        this.timeoutComando = timeoutComando;
        this.ttlBlocos = bytes(Long.toString(properties.ttl().toMillis()));
        this.ttlCustodiaEmMilissegundos = properties.ttlCustodia().toMillis();
        this.ttlCustodia = bytes(Long.toString(ttlCustodiaEmMilissegundos));
        this.chavesPorComando = properties.chavesPorComando();
        this.maxChavesPorConsulta = properties.maxChavesPorConsulta();
        this.prazoMinimoRestante = properties.prazoMinimoRestante();
        this.vagasDeGravacao = new Semaphore(properties.maxGravacoesSimultaneas());
        this.gravacoes = gravacoes;
        for (var resultado : Resultado.values()) {
            contadores.put(resultado, contadores(meterRegistry, resultado));
        }
    }

    /** Hash de custódia e os quatro hashes de blocos (um por custódia) num só pipeline. */
    @Override
    public LeituraDaOperacao buscarOperacao(long idOperacao, Set<Bloco> blocos) {
        var pedidos = List.copyOf(blocos);
        if (pedidos.size() > maxChavesPorConsulta || !PrazoDaRequisicao.comporta(prazoMinimoRestante)) {
            contar(pedidos, Resultado.IGNORADO);
            return LeituraDaOperacao.VAZIA;
        }
        var leituras = new ArrayList<LeituraDeHash>(1 + Custodia.values().length);
        leituras.add(new LeituraDeHash(bytes(CacheDeCustodia.chaveVersionada(idOperacao)), CAMPOS_DA_CUSTODIA));
        var campos = formato.campos(pedidos);
        for (var custodia : Custodia.values()) {
            leituras.add(new LeituraDeHash(formato.chave(new ChaveDeOperacao(idOperacao, custodia)), campos));
        }
        var valores = executar("HMGET", null, () -> hmget(leituras));
        if (valores.isEmpty()) {
            contar(pedidos, Resultado.INDISPONIVEL);
            return LeituraDaOperacao.VAZIA;
        }
        var lembrada = custodiaLembrada(valores.get().getFirst());
        if (lembrada.isEmpty()) {
            contar(pedidos, Resultado.MISS);
            return LeituraDaOperacao.VAZIA;
        }
        var emCache = formato.ler(valores.get().get(1 + lembrada.get().custodia().ordinal()), pedidos);
        contar(pedidos, emCache);
        return new LeituraDaOperacao(lembrada, emCache);
    }

    @Override
    public Map<ChaveDeOperacao, BlocosEmCache> buscar(Map<ChaveDeOperacao, OptionalLong> versoesExigidas,
                                                      Set<Bloco> blocos) {
        if (versoesExigidas.isEmpty() || blocos.isEmpty()) {
            return Map.of();
        }
        var pedidos = List.copyOf(blocos);
        var chaves = List.copyOf(versoesExigidas.keySet());
        if ((long) chaves.size() * pedidos.size() > maxChavesPorConsulta
                || !PrazoDaRequisicao.comporta(prazoMinimoRestante)) {
            contar(pedidos, chaves.size(), Resultado.IGNORADO);
            return Map.of();
        }
        var campos = formato.campos(pedidos);
        var leituras = chaves.stream().map(chave -> new LeituraDeHash(formato.chave(chave), campos)).toList();
        var valores = executar("HMGET", null, () -> hmget(leituras));
        if (valores.isEmpty()) {
            contar(pedidos, chaves.size(), Resultado.INDISPONIVEL);
            return Map.of();
        }
        var encontrados = new HashMap<ChaveDeOperacao, BlocosEmCache>();
        for (int i = 0; i < chaves.size(); i++) {
            var chave = chaves.get(i);
            var emCache = formato.ler(valores.get().get(i), pedidos)
                    .map(blocosDoHash -> naVersao(blocosDoHash, versoesExigidas.get(chave)))
                    .filter(blocosDoHash -> !blocosDoHash.blocos().isEmpty());
            contar(pedidos, emCache);
            emCache.ifPresent(blocosDoHash -> encontrados.put(chave, blocosDoHash));
        }
        return encontrados;
    }

    /**
     * Agenda a gravação e volta na hora. Descartada (sem erro) com blocos demais, sem vaga de gravação ou com o
     * circuito aberto.
     */
    @Override
    public void gravar(Collection<GravacaoDeBlocos> blocos, Collection<GravacaoDeCustodia> custodias) {
        var comandosDeGravacao = new ArrayList<Supplier<CompletableFuture<?>>>();
        var total = custodias.size();
        for (var gravacao : blocos) {
            if (gravacao.blocos().isEmpty()) {
                continue;
            }
            var argumentos = formato.argumentos(gravacao, ttlBlocos);
            if (argumentos.isEmpty()) {
                log.debug("Blocos da operação {} em versões diferentes; hash de versão única não gravado",
                        gravacao.chave().idOperacao());
                continue;
            }
            total += gravacao.blocos().size();
            var chave = formato.chave(gravacao.chave());
            var script = formato.script();
            comandosDeGravacao.add(() -> comandos.executar(script, chave, argumentos.get()));
        }
        for (var custodia : custodias) {
            var codigo = bytes(custodia.custodia().codigo());
            var versionada = bytes(CacheDeCustodia.chaveVersionada(custodia.idOperacao()));
            byte[][] argumentos = {bytes(Long.toString(custodia.versao())), ttlCustodia, bytes(CAMPO_CUSTODIA), codigo};
            comandosDeGravacao.add(() -> comandos.executar(ScriptValkey.GRAVAR_VERSIONADO, versionada, argumentos));
            // Contrato com a fonte primária (ADR-0017): só o código, sem versão
            var compartilhada = bytes(CacheDeCustodia.chave(custodia.idOperacao()));
            comandosDeGravacao.add(() -> comandos.set(compartilhada, codigo, ttlCustodiaEmMilissegundos));
        }
        if (total == 0 || total > maxChavesPorConsulta) {
            return;
        }
        agendar("EVALSHA", total, () -> emRodadas(comandosDeGravacao));
    }

    @Override
    public void remover(long idOperacao) {
        var versionada = bytes(CacheDeCustodia.chaveVersionada(idOperacao));
        var compartilhada = bytes(CacheDeCustodia.chave(idOperacao));
        agendar("DEL", 2, () -> aguardar(List.of(comandos.del(versionada), comandos.del(compartilhada)),
                timeoutComando));
    }

    /** Executa {@code comando} fora da requisição, se houver vaga de gravação; senão descarta. */
    private void agendar(String comando, int chaves, Runnable acao) {
        if (!vagasDeGravacao.tryAcquire()) {
            log.debug("Gravações no Valkey todas em andamento; {} chaves não gravadas ({})", chaves, comando);
            return;
        }
        var pai = observationRegistry.getCurrentObservation();
        try {
            gravacoes.execute(() -> {
                try {
                    executar(comando, pai, () -> {
                        acao.run();
                        return Boolean.TRUE;
                    });
                } finally {
                    vagasDeGravacao.release();
                }
            });
        } catch (RejectedExecutionException e) {
            vagasDeGravacao.release();
        }
    }

    /** Custódia do hash {@code custodia-vc}; sem lembrança, com marcador ou fora do formato: vazia. */
    private static Optional<CustodiaLembrada> custodiaLembrada(List<byte[]> valores) {
        var custodia = valores.get(0);
        var versao = versao(valores.get(1));
        if (custodia == null || versao.isEmpty() || valores.get(2) != null) {
            return Optional.empty();
        }
        return Custodia.porCodigo(new String(custodia, StandardCharsets.UTF_8))
                .map(encontrada -> new CustodiaLembrada(encontrada, versao.get()));
    }

    /** Só os blocos na versão exigida; sem versão exigida, todos. */
    private static BlocosEmCache naVersao(BlocosEmCache blocosDoHash, OptionalLong exigida) {
        if (exigida.isEmpty()) {
            return blocosDoHash;
        }
        var naVersao = new EnumMap<Bloco, BlocoVersionado>(Bloco.class);
        blocosDoHash.blocos().forEach((bloco, versionado) -> {
            if (versionado.versao() == exigida.getAsLong()) {
                naVersao.put(bloco, versionado);
            }
        });
        return new BlocosEmCache(naVersao);
    }

    private static Optional<Long> versao(byte[] valor) {
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(new String(valor, StandardCharsets.UTF_8)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    /** Um {@code HMGET} de um hash. */
    private record LeituraDeHash(byte[] chave, byte[][] campos) {
    }

    /** Todos os {@code HMGET}s em pipeline, esperados juntos; uma lista de valores por hash, na ordem pedida. */
    private List<List<byte[]>> hmget(List<LeituraDeHash> leituras) {
        var futuros = new ArrayList<CompletableFuture<List<byte[]>>>(leituras.size());
        for (var leitura : leituras) {
            futuros.add(comandos.hmget(leitura.chave(), leitura.campos()));
        }
        aguardar(futuros, PrazoDaRequisicao.limitar(timeoutComando));
        var valores = new ArrayList<List<byte[]>>(leituras.size());
        for (int i = 0; i < futuros.size(); i++) {
            var doHash = futuros.get(i).join();
            if (doHash.size() != leituras.get(i).campos().length) {
                throw new IllegalStateException("HMGET devolveu " + doHash.size() + " valores para "
                        + leituras.get(i).campos().length + " campos");
            }
            valores.add(doHash);
        }
        return valores;
    }

    /** Comandos em rodadas de até {@code chaves-por-comando} em pipeline, cada rodada no timeout de comando. */
    private void emRodadas(List<Supplier<CompletableFuture<?>>> comandosDeGravacao) {
        for (int inicio = 0; inicio < comandosDeGravacao.size(); inicio += chavesPorComando) {
            var futuros = new ArrayList<CompletableFuture<?>>();
            for (var comando : comandosDeGravacao.subList(inicio,
                    Math.min(inicio + chavesPorComando, comandosDeGravacao.size()))) {
                futuros.add(comando.get());
            }
            aguardar(futuros, timeoutComando);
        }
    }

    /** Espera todos os futuros por até {@code timeout} no total; o primeiro que falhar derruba a espera. */
    private static void aguardar(List<? extends CompletableFuture<?>> futuros, Duration timeout) {
        try {
            CompletableFuture.allOf(futuros.toArray(CompletableFuture[]::new))
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futuros.forEach(futuro -> futuro.cancel(false));
            throw new RedisCommandTimeoutException("Valkey sem resposta em " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new IllegalStateException("Falha no Valkey", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisCommandInterruptedException(e);
        }
    }

    /** Executa a chamada sob o circuit breaker; qualquer falha vira {@code Optional.empty()} (fail-open). */
    private <T> Optional<T> executar(String operacao, Observation pai, Supplier<T> chamada) {
        try {
            return Optional.ofNullable(circuitBreaker.executeSupplier(() ->
                    Observacoes.dependencia(observationRegistry, DEPENDENCIA, operacao)
                            .parentObservation(pai)
                            .observe(chamada)));
        } catch (CallNotPermittedException e) {
            log.debug("Circuit breaker do Valkey aberto; {} ignorado, consulta segue no DynamoDB", operacao);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Falha no Valkey ({}); consulta segue no DynamoDB: {}", operacao, e.toString());
            return Optional.empty();
        }
    }

    /** Hit para cada bloco pedido presente no hash; miss para os demais (ou todos, sem hash válido). */
    private void contar(List<Bloco> pedidos, Optional<BlocosEmCache> emCache) {
        for (var bloco : pedidos) {
            var hit = emCache.map(blocosDoHash -> blocosDoHash.blocos().containsKey(bloco)).orElse(false);
            contar(bloco, hit ? Resultado.HIT : Resultado.MISS);
        }
    }

    private void contar(List<Bloco> pedidos, Resultado resultado) {
        contar(pedidos, 1, resultado);
    }

    private void contar(List<Bloco> pedidos, int operacoes, Resultado resultado) {
        for (int i = 0; i < operacoes; i++) {
            pedidos.forEach(bloco -> contar(bloco, resultado));
        }
    }

    private void contar(Bloco bloco, Resultado resultado) {
        contadores.get(resultado).get(bloco).increment();
        if (resultado == Resultado.HIT) {
            CacheDaRequisicao.hit(bloco.nome());
        } else {
            CacheDaRequisicao.miss(bloco.nome());
        }
    }

    private static Map<Bloco, Counter> contadores(MeterRegistry registry, Resultado resultado) {
        var contadores = new EnumMap<Bloco, Counter>(Bloco.class);
        for (var bloco : Bloco.values()) {
            contadores.put(bloco, Counter.builder(METRICA_CACHE)
                    .description("Blocos procurados no cache da visão consolidada")
                    .tag("bloco", bloco.nome())
                    .tag("resultado", resultado.tag())
                    .register(registry));
        }
        return contadores;
    }
}
