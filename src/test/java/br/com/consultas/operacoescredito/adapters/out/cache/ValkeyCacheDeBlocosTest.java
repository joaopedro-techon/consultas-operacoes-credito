package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.observabilidade.CacheDaRequisicao;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache com um Valkey em memória que responde, atrasa ou falha como o teste mandar: divisão em comandos pequenos
 * em pipeline, consultas grandes e prazo curto sem tocar no Valkey, gravação fora da requisição e resultado
 * {@code indisponivel} na métrica.
 */
class ValkeyCacheDeBlocosTest {

    private static final Duration TIMEOUT_COMANDO = Duration.ofMillis(500);

    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private final ValkeyFalso valkey = new ValkeyFalso();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("valkey", CircuitBreakerConfig.custom()
            .slidingWindowSize(100).minimumNumberOfCalls(100).build());
    private final List<Runnable> gravacoesAgendadas = new CopyOnWriteArrayList<>();

    @AfterEach
    void limpar() {
        PrazoDaRequisicao.encerrar();
        CacheDaRequisicao.encerrar();
    }

    @Test
    void leituraGrandeViraMgetsDeAteCemChavesEmPipeline() {
        var cache = cache(Runnable::run, 500);
        var chaves = chaves(250);
        valkey.guardar(chaves.get(0), "a");
        valkey.guardar(chaves.get(249), "b");

        var encontrados = cache.buscar(chaves);

        assertThat(valkey.tamanhosDosMget()).containsExactly(100, 100, 50);
        assertThat(valkey.maximoPendentes()).as("todos enviados antes da primeira resposta").isEqualTo(3);
        assertThat(encontrados).containsOnlyKeys(chaves.get(0), chaves.get(249));
        assertThat(metrica("hit")).isEqualTo(2);
        assertThat(metrica("miss")).isEqualTo(248);
    }

    @Test
    void gravacaoGrandeViraRodadasDeAteCemSetsComTtl() {
        var cache = cache(Runnable::run, 500);
        var blocos = new HashMap<ChaveDeBloco, byte[]>();
        chaves(250).forEach(chave -> blocos.put(chave, bytes("x")));

        cache.gravar(blocos);

        assertThat(valkey.sets()).hasValue(250);
        assertThat(valkey.maximoPendentes()).as("SETs em pipeline, no máximo 100 por rodada").isEqualTo(100);
        assertThat(valkey.ttls()).containsOnly(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void gravacaoNaoRodaNaThreadDaRequisicao() {
        var cache = cache(gravacoesAgendadas::add, 500);

        cache.gravar(Map.of(chaves(1).getFirst(), bytes("x")));

        assertThat(valkey.sets()).hasValue(0);
        assertThat(gravacoesAgendadas).hasSize(1);
        gravacoesAgendadas.getFirst().run();
        assertThat(valkey.sets()).hasValue(1);
    }

    @Test
    void semVagaDeGravacaoAGravacaoEDescartada() {
        var cache = cache(gravacoesAgendadas::add, 500);
        for (int i = 0; i < 2; i++) {
            cache.gravar(Map.of(chaves(1).getFirst(), bytes("x")));
        }

        // max-gravacoes-simultaneas = 1: a segunda nem foi agendada
        assertThat(gravacoesAgendadas).hasSize(1);
    }

    @Test
    void consultaComChavesDemaisNaoTocaNoValkeyEContaIgnorado() {
        var cache = cache(Runnable::run, 100);
        var contagem = CacheDaRequisicao.iniciar();

        assertThat(cache.buscar(chaves(101))).isEmpty();
        cache.gravar(Map.of(chaves(1).getFirst(), bytes("x")));
        var muitos = new HashMap<ChaveDeBloco, byte[]>();
        chaves(101).forEach(chave -> muitos.put(chave, bytes("x")));
        cache.gravar(muitos);

        assertThat(valkey.tamanhosDosMget()).isEmpty();
        assertThat(valkey.sets()).hasValue(1);
        assertThat(metrica("ignorado")).isEqualTo(101);
        assertThat(contagem.misses()).containsEntry("parcelas", 101L);
    }

    @Test
    void poucoPrazoRestanteNaoTocaNoValkey() {
        var cache = cache(Runnable::run, 500);
        PrazoDaRequisicao.iniciar(Duration.ofMillis(5));

        assertThat(cache.buscar(chaves(3))).isEmpty();

        assertThat(valkey.tamanhosDosMget()).isEmpty();
        assertThat(metrica("ignorado")).isEqualTo(3);
    }

    @Test
    void valkeyForaContaIndisponivelEFalhaNoCircuito() {
        var cache = cache(Runnable::run, 500);
        valkey.falharComConexao();
        var contagem = CacheDaRequisicao.iniciar();

        assertThat(cache.buscar(chaves(3))).isEmpty();

        assertThat(metrica("indisponivel")).isEqualTo(3);
        assertThat(metrica("miss")).isZero();
        assertThat(contagem.misses()).containsEntry("parcelas", 3L);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void circuitoAbertoContaIndisponivelSemChamarOValkey() {
        var cache = cache(Runnable::run, 500);
        circuitBreaker.transitionToOpenState();

        assertThat(cache.buscar(chaves(2))).isEmpty();

        assertThat(valkey.tamanhosDosMget()).isEmpty();
        assertThat(metrica("indisponivel")).isEqualTo(2);
    }

    @Test
    void valkeySemRespostaDesisteNoTimeoutDeComando() {
        var cache = cache(Runnable::run, 500);
        valkey.naoResponder();

        var inicio = System.nanoTime();
        assertThat(cache.buscar(chaves(3))).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        assertThat(metrica("indisponivel")).isEqualTo(3);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private ValkeyCacheDeBlocos cache(Executor gravacoes, int maxChavesPorConsulta) {
        // Uma gravação em andamento por vez
        var properties = new CacheProperties(true, Duration.ofMinutes(5), 100, maxChavesPorConsulta,
                Duration.ofMillis(20), 1);
        return new ValkeyCacheDeBlocos(valkey, circuitBreaker, ObservationRegistry.NOOP, metricas, properties,
                TIMEOUT_COMANDO, gravacoes);
    }

    private double metrica(String resultado) {
        return metricas.find(ValkeyCacheDeBlocos.METRICA_CACHE).tag("resultado", resultado).counters().stream()
                .mapToDouble(contador -> contador.count()).sum();
    }

    private static List<ChaveDeBloco> chaves(int quantidade) {
        var chaves = new ArrayList<ChaveDeBloco>();
        for (int i = 1; i <= quantidade; i++) {
            chaves.add(new ChaveDeBloco(i, Bloco.PARCELAS));
        }
        return chaves;
    }

    private static byte[] bytes(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Valkey em memória. Cada resposta sai {@link #DEMORA} depois do comando, então os comandos enviados em
     * sequência sem esperar (pipeline) ficam pendentes juntos: o máximo de pendentes mostra o tamanho do pipeline.
     */
    private static final class ValkeyFalso implements ComandosValkey {

        private static final long DEMORA = 30;

        private final Map<String, byte[]> dados = new ConcurrentHashMap<>();
        private final List<Integer> tamanhosDosMget = new CopyOnWriteArrayList<>();
        private final List<Long> ttls = new CopyOnWriteArrayList<>();
        private final AtomicInteger sets = new AtomicInteger();
        private final AtomicInteger pendentes = new AtomicInteger();
        private final AtomicInteger maximoPendentes = new AtomicInteger();
        private volatile boolean semConexao;
        private volatile boolean semResposta;

        void guardar(ChaveDeBloco chave, String valor) {
            dados.put(chave.valor(), bytes(valor));
        }

        void falharComConexao() {
            semConexao = true;
        }

        void naoResponder() {
            semResposta = true;
        }

        List<Integer> tamanhosDosMget() {
            return tamanhosDosMget;
        }

        int maximoPendentes() {
            return maximoPendentes.get();
        }

        AtomicInteger sets() {
            return sets;
        }

        List<Long> ttls() {
            return ttls;
        }

        @Override
        public CompletableFuture<List<byte[]>> mget(byte[][] chaves) {
            if (semConexao) {
                throw new RedisConnectionFailureException("Valkey fora");
            }
            tamanhosDosMget.add(chaves.length);
            var valores = new ArrayList<byte[]>();
            for (var chave : chaves) {
                valores.add(dados.get(new String(chave, StandardCharsets.UTF_8)));
            }
            return responder(valores);
        }

        @Override
        public CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos) {
            sets.incrementAndGet();
            ttls.add(ttlMilissegundos);
            dados.put(new String(chave, StandardCharsets.UTF_8), valor);
            return responder(Boolean.TRUE);
        }

        private <T> CompletableFuture<T> responder(T valor) {
            var futuro = new CompletableFuture<T>();
            maximoPendentes.accumulateAndGet(pendentes.incrementAndGet(), Math::max);
            if (!semResposta) {
                CompletableFuture.delayedExecutor(DEMORA, TimeUnit.MILLISECONDS).execute(() -> {
                    pendentes.decrementAndGet();
                    futuro.complete(valor);
                });
            }
            return futuro;
        }
    }
}