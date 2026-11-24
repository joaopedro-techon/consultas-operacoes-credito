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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Cache por bloco no Valkey: chave {@code op:{id_operacao}:{bloco}}, valor = {@code texto_detalhe} GZIP como veio
 * do DynamoDB, com TTL.
 * <ul>
 *     <li>Leitura, na requisição: um {@code MGET} por grupo de até {@code chaves-por-comando} chaves, todos em
 *     pipeline e esperados juntos, no máximo pelo timeout de comando ({@code spring.data.redis.timeout}). Não lê
 *     com menos de {@code prazo-minimo-restante} do prazo da requisição, nem com mais de
 *     {@code max-chaves-por-consulta} chaves.</li>
 *     <li>Gravação, fora da requisição (virtual thread, no máximo {@code max-gravacoes-simultaneas}): um
 *     {@code SET ... PX} por bloco, em rodadas de até {@code chaves-por-comando} comandos em pipeline, cada rodada
 *     dentro do timeout de comando. A resposta da consulta não espera a gravação.</li>
 * </ul>
 * Só comandos de uma chave e {@code MGET} (ADR-0011). Fail-open: toda leitura e cada gravação passam pelo circuit
 * breaker {@value CacheConfig#CIRCUIT_BREAKER}; falha ou circuito aberto viram "nada em cache" e a consulta segue
 * no DynamoDB.
 * <p>
 * Cada leitura e gravação é uma observação {@code consultas.dependencia} ({@code dependencia=valkey},
 * {@code operacao=MGET|SET}); cada bloco procurado conta no contador {@code consultas.cache} ({@code bloco},
 * {@code resultado=hit|miss|indisponivel|ignorado}) e na contagem da requisição ({@link CacheDaRequisicao}, que
 * segue no evento de consumo: lá só há hits e misses, e indisponível ou ignorado conta como miss).
 */
class ValkeyCacheDeBlocos implements CacheConfig.CacheDeBlocosECustodia {

    static final String DEPENDENCIA = "valkey";
    static final String METRICA_CACHE = "consultas.cache";

    private static final Logger log = LoggerFactory.getLogger(ValkeyCacheDeBlocos.class);

    /** Valor da tag {@code resultado} de {@code consultas.cache}. */
    enum Resultado {
        /** Bloco encontrado no cache. */
        HIT,
        /** Bloco procurado e ausente no cache. */
        MISS,
        /** Cache não respondeu: Valkey fora, lento ou circuit breaker aberto. */
        INDISPONIVEL,
        /** Cache não consultado de propósito: chaves demais na consulta ou pouco prazo restante. */
        IGNORADO;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ComandosValkey comandos;
    private final CircuitBreaker circuitBreaker;
    private final ObservationRegistry observationRegistry;
    private final Duration timeoutComando;
    private final long ttlEmMilissegundos;
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
        this.ttlEmMilissegundos = properties.ttl().toMillis();
        this.ttlCustodiaEmMilissegundos = properties.ttlCustodia().toMillis();
        this.chavesPorComando = properties.chavesPorComando();
        this.maxChavesPorConsulta = properties.maxChavesPorConsulta();
        this.prazoMinimoRestante = properties.prazoMinimoRestante();
        this.vagasDeGravacao = new Semaphore(properties.maxGravacoesSimultaneas());
        this.gravacoes = gravacoes;
        for (var resultado : Resultado.values()) {
            contadores.put(resultado, contadores(meterRegistry, resultado));
        }
    }

    @Override
    public LeituraDoCache buscar(Collection<ChaveDeBloco> chaves, Collection<Long> custodiasDe) {
        if (chaves.isEmpty() && custodiasDe.isEmpty()) {
            return LeituraDoCache.VAZIA;
        }
        var ordenadas = List.copyOf(chaves);
        var ids = List.copyOf(custodiasDe);
        if (ordenadas.size() + ids.size() > maxChavesPorConsulta || !PrazoDaRequisicao.comporta(prazoMinimoRestante)) {
            contar(ordenadas, Resultado.IGNORADO);
            return LeituraDoCache.VAZIA;
        }
        var todas = new ArrayList<byte[]>(ordenadas.size() + ids.size());
        ordenadas.forEach(chave -> todas.add(chave.bytes()));
        ids.forEach(id -> todas.add(bytes(CacheDeCustodia.chave(id))));
        var valores = executar("MGET", null, () -> mget(todas));
        if (valores.isEmpty()) {
            contar(ordenadas, Resultado.INDISPONIVEL);
            return LeituraDoCache.VAZIA;
        }
        var encontrados = new HashMap<ChaveDeBloco, byte[]>();
        for (int i = 0; i < ordenadas.size(); i++) {
            var chave = ordenadas.get(i);
            var valor = valores.get().get(i);
            if (valor != null) {
                encontrados.put(chave, valor);
                contar(chave, Resultado.HIT);
            } else {
                contar(chave, Resultado.MISS);
            }
        }
        var custodias = new HashMap<Long, Custodia>();
        for (int i = 0; i < ids.size(); i++) {
            var id = ids.get(i);
            var valor = valores.get().get(ordenadas.size() + i);
            if (valor != null) {
                // Valor fora do formato (outra versão, edição manual) é tratado como ausente
                Custodia.porCodigo(new String(valor, StandardCharsets.UTF_8))
                        .ifPresent(custodia -> custodias.put(id, custodia));
            }
        }
        return new LeituraDoCache(encontrados, custodias);
    }

    @Override
    public Optional<Custodia> buscar(long idOperacao) {
        return Optional.ofNullable(buscar(List.of(), List.of(idOperacao)).custodias().get(idOperacao));
    }

    @Override
    public void gravar(long idOperacao, Custodia custodia) {
        gravar(Map.of(), Map.of(idOperacao, custodia));
    }

    @Override
    public void gravarTodas(Map<Long, Custodia> custodias) {
        gravar(Map.of(), custodias);
    }

    @Override
    public void remover(long idOperacao) {
        var chave = bytes(CacheDeCustodia.chave(idOperacao));
        agendar("DEL", 1, () -> aguardar(List.of(comandos.del(chave)), timeoutComando));
    }

    /**
     * Agenda a gravação e volta na hora. Descartada (sem erro) com chaves demais, sem vaga de gravação ou com o
     * circuito aberto.
     */
    @Override
    public void gravar(Map<ChaveDeBloco, byte[]> blocos, Map<Long, Custodia> custodias) {
        var total = blocos.size() + custodias.size();
        if (total == 0 || total > maxChavesPorConsulta) {
            return;
        }
        var valores = new ArrayList<Gravacao>(total);
        blocos.forEach((chave, valor) -> valores.add(new Gravacao(chave.bytes(), valor, ttlEmMilissegundos)));
        custodias.forEach((id, custodia) -> valores.add(new Gravacao(bytes(CacheDeCustodia.chave(id)),
                bytes(custodia.codigo()), ttlCustodiaEmMilissegundos)));
        agendar("SET", total, () -> set(valores));
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

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    /** Um {@code SET chave valor PX ttl}. */
    private record Gravacao(byte[] chave, byte[] valor, long ttlMilissegundos) {
    }

    /** {@code MGET}s de até {@code chaves-por-comando} chaves em pipeline, esperados juntos. */
    private List<byte[]> mget(List<byte[]> chaves) {
        var futuros = new ArrayList<CompletableFuture<List<byte[]>>>();
        for (int inicio = 0; inicio < chaves.size(); inicio += chavesPorComando) {
            var grupo = chaves.subList(inicio, Math.min(inicio + chavesPorComando, chaves.size()));
            futuros.add(comandos.mget(grupo.toArray(byte[][]::new)));
        }
        aguardar(futuros, PrazoDaRequisicao.limitar(timeoutComando));
        var valores = new ArrayList<byte[]>(chaves.size());
        for (var futuro : futuros) {
            valores.addAll(futuro.join());
        }
        if (valores.size() != chaves.size()) {
            throw new IllegalStateException("MGET devolveu " + valores.size() + " valores para " + chaves.size()
                    + " chaves");
        }
        return valores;
    }

    /** {@code SET ... PX} em rodadas de até {@code chaves-por-comando} comandos em pipeline. */
    private void set(List<Gravacao> gravacoes) {
        for (int inicio = 0; inicio < gravacoes.size(); inicio += chavesPorComando) {
            var futuros = new ArrayList<CompletableFuture<?>>();
            for (var gravacao : gravacoes.subList(inicio, Math.min(inicio + chavesPorComando, gravacoes.size()))) {
                futuros.add(comandos.set(gravacao.chave(), gravacao.valor(), gravacao.ttlMilissegundos()));
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

    private void contar(List<ChaveDeBloco> chaves, Resultado resultado) {
        chaves.forEach(chave -> contar(chave, resultado));
    }

    private void contar(ChaveDeBloco chave, Resultado resultado) {
        contadores.get(resultado).get(chave.bloco()).increment();
        if (resultado == Resultado.HIT) {
            CacheDaRequisicao.hit(chave.bloco().nome());
        } else {
            CacheDaRequisicao.miss(chave.bloco().nome());
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
