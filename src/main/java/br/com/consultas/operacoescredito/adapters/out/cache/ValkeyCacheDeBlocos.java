package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.observabilidade.CacheDaRequisicao;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;
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
 * Cache versionado no Valkey (ADR-0018):
 * <ul>
 *     <li>blocos: hash {@code op:{id_operacao}:{custodia}} com {@code versao} (epoch ms), um campo por bloco
 *     ({@code texto_detalhe} GZIP como veio do DynamoDB) e, se invalidado, {@code marcador}; TTL {@code ttl};</li>
 *     <li>custódia: hash {@code custodia-vc:{id_operacao}} com {@code custodia}, {@code versao} e, se invalidado,
 *     {@code marcador}; TTL {@code ttl-custodia}. A chave compartilhada {@code custodia:{id_operacao}} (ADR-0017)
 *     continua sendo gravada como antes, mas não é lida.</li>
 * </ul>
 * Leitura, na requisição: um {@code HMGET} por hash, todos em pipeline e esperados juntos, no máximo pelo timeout
 * de comando ({@code spring.data.redis.timeout}). Um {@code HMGET} é atômico, então os blocos lidos de um hash são
 * sempre da mesma versão. Não lê com menos de {@code prazo-minimo-restante} do prazo da requisição, nem com mais de
 * {@code max-chaves-por-consulta} blocos (operações × blocos).
 * <p>
 * Gravação, fora da requisição (virtual thread, no máximo {@code max-gravacoes-simultaneas}): o script
 * {@link ScriptValkey#GRAVAR_VERSIONADO} por hash, em rodadas de até {@code chaves-por-comando} comandos em pipeline,
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

    ValkeyCacheDeBlocos(ComandosValkey comandos, CircuitBreaker circuitBreaker, ObservationRegistry observationRegistry,
                        MeterRegistry meterRegistry, CacheProperties properties, Duration timeoutComando,
                        Executor gravacoes) {
        this.comandos = comandos;
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
        var campos = camposDosBlocos(pedidos);
        for (var custodia : Custodia.values()) {
            leituras.add(new LeituraDeHash(new ChaveDeOperacao(idOperacao, custodia).bytes(), campos));
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
        var emCache = blocosEmCache(valores.get().get(1 + lembrada.get().custodia().ordinal()), pedidos);
        contar(pedidos, emCache);
        return new LeituraDaOperacao(lembrada, emCache);
    }

    @Override
    public Map<ChaveDeOperacao, BlocosEmCache> buscar(Map<ChaveDeOperacao, Long> versoes, Set<Bloco> blocos) {
        if (versoes.isEmpty() || blocos.isEmpty()) {
            return Map.of();
        }
        var pedidos = List.copyOf(blocos);
        var chaves = List.copyOf(versoes.keySet());
        if ((long) chaves.size() * pedidos.size() > maxChavesPorConsulta
                || !PrazoDaRequisicao.comporta(prazoMinimoRestante)) {
            contar(pedidos, chaves.size(), Resultado.IGNORADO);
            return Map.of();
        }
        var campos = camposDosBlocos(pedidos);
        var leituras = chaves.stream().map(chave -> new LeituraDeHash(chave.bytes(), campos)).toList();
        var valores = executar("HMGET", null, () -> hmget(leituras));
        if (valores.isEmpty()) {
            contar(pedidos, chaves.size(), Resultado.INDISPONIVEL);
            return Map.of();
        }
        var encontrados = new HashMap<ChaveDeOperacao, BlocosEmCache>();
        for (int i = 0; i < chaves.size(); i++) {
            var chave = chaves.get(i);
            var emCache = blocosEmCache(valores.get().get(i), pedidos)
                    .filter(blocosDoHash -> blocosDoHash.versao() == versoes.get(chave));
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
            total += gravacao.blocos().size();
            var argumentos = new ArrayList<byte[]>(2 + 2 * gravacao.blocos().size());
            argumentos.add(bytes(Long.toString(gravacao.versao())));
            argumentos.add(ttlBlocos);
            gravacao.blocos().forEach((bloco, textoDetalhe) -> {
                argumentos.add(bytes(bloco.nome()));
                argumentos.add(textoDetalhe);
            });
            var chave = gravacao.chave().bytes();
            comandosDeGravacao.add(() -> comandos.executar(ScriptValkey.GRAVAR_VERSIONADO, chave,
                    argumentos.toArray(byte[][]::new)));
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

    /**
     * Blocos do hash de uma operação: os dois primeiros valores são {@code versao} e {@code marcador}, depois um por
     * bloco pedido. Hash inexistente, com marcador ou com versão fora do formato: vazio.
     */
    private static Optional<BlocosEmCache> blocosEmCache(List<byte[]> valores, List<Bloco> pedidos) {
        var versao = versao(valores.get(0));
        if (versao.isEmpty() || valores.get(1) != null) {
            return Optional.empty();
        }
        var blocos = new EnumMap<Bloco, byte[]>(Bloco.class);
        for (int i = 0; i < pedidos.size(); i++) {
            var valor = valores.get(2 + i);
            if (valor != null) {
                blocos.put(pedidos.get(i), valor);
            }
        }
        return Optional.of(new BlocosEmCache(versao.get(), blocos));
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

    private static byte[][] camposDosBlocos(List<Bloco> pedidos) {
        var campos = new byte[2 + pedidos.size()][];
        campos[0] = bytes(CAMPO_VERSAO);
        campos[1] = bytes(CAMPO_MARCADOR);
        for (int i = 0; i < pedidos.size(); i++) {
            campos[2 + i] = bytes(pedidos.get(i).nome());
        }
        return campos;
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
