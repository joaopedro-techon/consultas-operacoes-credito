package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.GravacaoDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.GravacaoDeCustodia;
import br.com.consultas.operacoescredito.config.observabilidade.CacheDaRequisicao;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;
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
import java.util.EnumSet;
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
 * Cache com um Valkey em memória que responde, atrasa ou falha como o teste mandar: um {@code HMGET} por hash em
 * pipeline, só a versão esperada conta como hit, marcador conta como ausente, gravação pelo script versionado fora
 * da requisição, consultas grandes e prazo curto sem tocar no Valkey e resultado {@code indisponivel} na métrica.
 * A regra de versão do script em si é verificada contra um Valkey de verdade, em {@code ScriptValkeyIT}.
 */
class ValkeyCacheDeBlocosTest {

    private static final Duration TIMEOUT_COMANDO = Duration.ofMillis(500);
    private static final long VERSAO = 1_764_000_000_000L;

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
    void leituraDeVariasOperacoesEUmHmgetPorHashEmPipeline() {
        var cache = cache(Runnable::run, 500);
        var versoes = versoes(30);
        valkey.hash("op:1:SF", Map.of("versao", Long.toString(VERSAO), "parcelas", "a"));
        valkey.hash("op:30:SF", Map.of("versao", Long.toString(VERSAO), "parcelas", "b", "saldo", "c"));

        var encontrados = cache.buscar(versoes, EnumSet.of(Bloco.PARCELAS, Bloco.SALDO));

        assertThat(valkey.hmgets()).hasValue(30);
        assertThat(valkey.maximoPendentes()).as("todos enviados antes da primeira resposta").isEqualTo(30);
        assertThat(encontrados).containsOnlyKeys(new ChaveDeOperacao(1, Custodia.SF),
                new ChaveDeOperacao(30, Custodia.SF));
        assertThat(encontrados.get(new ChaveDeOperacao(30, Custodia.SF)).blocos())
                .containsOnlyKeys(Bloco.PARCELAS, Bloco.SALDO);
        assertThat(metrica("hit")).isEqualTo(3);
        assertThat(metrica("miss")).isEqualTo(57);
    }

    @Test
    void hashDeOutraVersaoOuComMarcadorContaComoAusente() {
        var cache = cache(Runnable::run, 500);
        valkey.hash("op:1:SF", Map.of("versao", Long.toString(VERSAO - 1), "parcelas", "antigo"));
        valkey.hash("op:2:SF", Map.of("versao", Long.toString(VERSAO), "marcador", "1"));

        var encontrados = cache.buscar(versoes(2), EnumSet.of(Bloco.PARCELAS));

        assertThat(encontrados).isEmpty();
        assertThat(metrica("miss")).isEqualTo(2);
    }

    @Test
    void consultaPorOperacaoLeCustodiaEAsQuatroCustodiasNumSoPipeline() {
        var cache = cache(Runnable::run, 500);
        valkey.hash("custodia-vc:1", Map.of("custodia", "NSF", "versao", Long.toString(VERSAO)));
        valkey.hash("op:1:OD", Map.of("versao", Long.toString(VERSAO - 5), "operacao", "da OD"));
        valkey.hash("op:1:NSF", Map.of("versao", Long.toString(VERSAO), "operacao", "da NSF"));

        var leitura = cache.buscarOperacao(1, EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS));

        assertThat(valkey.maximoPendentes()).isEqualTo(5);
        assertThat(leitura.custodia()).hasValueSatisfying(lembrada -> {
            assertThat(lembrada.custodia()).isEqualTo(Custodia.NSF);
            assertThat(lembrada.versao()).isEqualTo(VERSAO);
        });
        assertThat(leitura.blocos()).hasValueSatisfying(blocos -> {
            assertThat(blocos.versao()).isEqualTo(VERSAO);
            assertThat(texto(blocos.blocos().get(Bloco.OPERACAO))).isEqualTo("da NSF");
            assertThat(blocos.blocos()).doesNotContainKey(Bloco.PARCELAS);
        });
        assertThat(metrica("hit")).isEqualTo(1);
        assertThat(metrica("miss")).isEqualTo(1);
    }

    @Test
    void custodiaComMarcadorNaoELembrada() {
        var cache = cache(Runnable::run, 500);
        valkey.hash("custodia-vc:1", Map.of("versao", Long.toString(VERSAO), "marcador", "1"));
        valkey.hash("op:1:SF", Map.of("versao", Long.toString(VERSAO), "operacao", "x"));

        var leitura = cache.buscarOperacao(1, EnumSet.of(Bloco.OPERACAO));

        assertThat(leitura.custodia()).isEmpty();
        assertThat(leitura.blocos()).isEmpty();
    }

    @Test
    void gravacaoUsaOScriptVersionadoEmRodadasDeAteCemComTtl() {
        var cache = cache(Runnable::run, 500);
        var blocos = new ArrayList<GravacaoDeBlocos>();
        for (int id = 1; id <= 250; id++) {
            blocos.add(new GravacaoDeBlocos(new ChaveDeOperacao(id, Custodia.SF), VERSAO,
                    Map.of(Bloco.PARCELAS, bytes("x"))));
        }

        cache.gravar(blocos, List.of());

        assertThat(valkey.scripts()).hasSize(250);
        assertThat(valkey.maximoPendentes()).as("scripts em pipeline, no máximo 100 por rodada").isEqualTo(100);
        assertThat(valkey.scripts().getFirst()).satisfies(script -> {
            assertThat(script.chave()).isEqualTo("op:1:SF");
            assertThat(script.argumentos()).containsExactly(Long.toString(VERSAO),
                    Long.toString(Duration.ofMinutes(5).toMillis()), "parcelas", "x");
        });
    }

    @Test
    void gravacaoDaCustodiaVaiNaChaveVersionadaENaCompartilhadaComAFontePrimaria() {
        var cache = cache(Runnable::run, 500);

        cache.gravar(List.of(), List.of(new GravacaoDeCustodia(7, Custodia.F5, VERSAO)));

        assertThat(valkey.scripts()).singleElement().satisfies(script -> {
            assertThat(script.chave()).isEqualTo("custodia-vc:7");
            assertThat(script.argumentos()).containsExactly(Long.toString(VERSAO),
                    Long.toString(Duration.ofHours(24).toMillis()), "custodia", "F5");
        });
        assertThat(valkey.valor("custodia:7")).isEqualTo("F5");
        assertThat(valkey.ttls()).containsExactly(Duration.ofHours(24).toMillis());
    }

    @Test
    void gravacaoNaoRodaNaThreadDaRequisicao() {
        var cache = cache(gravacoesAgendadas::add, 500);

        cache.gravar(List.of(umaGravacao()), List.of());

        assertThat(valkey.scripts()).isEmpty();
        assertThat(gravacoesAgendadas).hasSize(1);
        gravacoesAgendadas.getFirst().run();
        assertThat(valkey.scripts()).hasSize(1);
    }

    @Test
    void semVagaDeGravacaoAGravacaoEDescartada() {
        var cache = cache(gravacoesAgendadas::add, 500);
        for (int i = 0; i < 2; i++) {
            cache.gravar(List.of(umaGravacao()), List.of());
        }

        // max-gravacoes-simultaneas = 1: a segunda nem foi agendada
        assertThat(gravacoesAgendadas).hasSize(1);
    }

    @Test
    void consultaComBlocosDemaisNaoTocaNoValkeyEContaIgnorado() {
        var cache = cache(Runnable::run, 100);
        var contagem = CacheDaRequisicao.iniciar();

        // 51 operações × 2 blocos = 102 > 100
        assertThat(cache.buscar(versoes(51), EnumSet.of(Bloco.PARCELAS, Bloco.SALDO))).isEmpty();

        assertThat(valkey.hmgets()).hasValue(0);
        assertThat(metrica("ignorado")).isEqualTo(102);
        assertThat(contagem.misses()).containsEntry("parcelas", 51L);
    }

    @Test
    void poucoPrazoRestanteNaoTocaNoValkey() {
        var cache = cache(Runnable::run, 500);
        PrazoDaRequisicao.iniciar(Duration.ofMillis(5));

        assertThat(cache.buscarOperacao(1, EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS)).custodia()).isEmpty();

        assertThat(valkey.hmgets()).hasValue(0);
        assertThat(metrica("ignorado")).isEqualTo(2);
    }

    @Test
    void valkeyForaContaIndisponivelEFalhaNoCircuito() {
        var cache = cache(Runnable::run, 500);
        valkey.falharComConexao();
        var contagem = CacheDaRequisicao.iniciar();

        assertThat(cache.buscar(versoes(3), EnumSet.of(Bloco.PARCELAS))).isEmpty();

        assertThat(metrica("indisponivel")).isEqualTo(3);
        assertThat(metrica("miss")).isZero();
        assertThat(contagem.misses()).containsEntry("parcelas", 3L);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void circuitoAbertoContaIndisponivelSemChamarOValkey() {
        var cache = cache(Runnable::run, 500);
        circuitBreaker.transitionToOpenState();

        assertThat(cache.buscar(versoes(2), EnumSet.of(Bloco.PARCELAS))).isEmpty();

        assertThat(valkey.hmgets()).hasValue(0);
        assertThat(metrica("indisponivel")).isEqualTo(2);
    }

    @Test
    void valkeySemRespostaDesisteNoTimeoutDeComando() {
        var cache = cache(Runnable::run, 500);
        valkey.naoResponder();

        var inicio = System.nanoTime();
        assertThat(cache.buscar(versoes(3), EnumSet.of(Bloco.PARCELAS))).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(1));
        assertThat(metrica("indisponivel")).isEqualTo(3);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private ValkeyCacheDeBlocos cache(Executor gravacoes, int maxChavesPorConsulta) {
        // Uma gravação em andamento por vez
        var properties = new CacheProperties(true, Duration.ofMinutes(5), 100, maxChavesPorConsulta,
                Duration.ofMillis(20), 1, Duration.ofHours(24));
        return new ValkeyCacheDeBlocos(valkey, circuitBreaker, ObservationRegistry.NOOP, metricas, properties,
                TIMEOUT_COMANDO, gravacoes);
    }

    private double metrica(String resultado) {
        return metricas.find(ValkeyCacheDeBlocos.METRICA_CACHE).tag("resultado", resultado).counters().stream()
                .mapToDouble(contador -> contador.count()).sum();
    }

    private static Map<ChaveDeOperacao, Long> versoes(int quantidade) {
        var versoes = new HashMap<ChaveDeOperacao, Long>();
        for (int i = 1; i <= quantidade; i++) {
            versoes.put(new ChaveDeOperacao(i, Custodia.SF), VERSAO);
        }
        return versoes;
    }

    private static GravacaoDeBlocos umaGravacao() {
        return new GravacaoDeBlocos(new ChaveDeOperacao(1, Custodia.SF), VERSAO, Map.of(Bloco.PARCELAS, bytes("x")));
    }

    private static byte[] bytes(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8);
    }

    private static String texto(byte[] valor) {
        return new String(valor, StandardCharsets.UTF_8);
    }

    /** Um script executado: a chave e os argumentos, como texto. */
    private record ScriptExecutado(String chave, List<String> argumentos) {
    }

    /**
     * Valkey em memória. Cada resposta sai {@link #DEMORA} depois do comando, então os comandos enviados em
     * sequência sem esperar (pipeline) ficam pendentes juntos: o máximo de pendentes mostra o tamanho do pipeline.
     * O script só é registrado, não executado.
     */
    private static final class ValkeyFalso implements ComandosValkey {

        private static final long DEMORA = 30;

        private final Map<String, Map<String, byte[]>> hashes = new ConcurrentHashMap<>();
        private final Map<String, byte[]> valores = new ConcurrentHashMap<>();
        private final List<ScriptExecutado> scripts = new CopyOnWriteArrayList<>();
        private final List<Long> ttls = new CopyOnWriteArrayList<>();
        private final AtomicInteger hmgets = new AtomicInteger();
        private final AtomicInteger pendentes = new AtomicInteger();
        private final AtomicInteger maximoPendentes = new AtomicInteger();
        private volatile boolean semConexao;
        private volatile boolean semResposta;

        void hash(String chave, Map<String, String> campos) {
            var hash = new ConcurrentHashMap<String, byte[]>();
            campos.forEach((campo, valor) -> hash.put(campo, bytes(valor)));
            hashes.put(chave, hash);
        }

        String valor(String chave) {
            var valor = valores.get(chave);
            return valor == null ? null : texto(valor);
        }

        void falharComConexao() {
            semConexao = true;
        }

        void naoResponder() {
            semResposta = true;
        }

        AtomicInteger hmgets() {
            return hmgets;
        }

        int maximoPendentes() {
            return maximoPendentes.get();
        }

        List<ScriptExecutado> scripts() {
            return scripts;
        }

        List<Long> ttls() {
            return ttls;
        }

        @Override
        public CompletableFuture<List<byte[]>> hmget(byte[] chave, byte[][] campos) {
            if (semConexao) {
                throw new RedisConnectionFailureException("Valkey fora");
            }
            hmgets.incrementAndGet();
            var hash = hashes.getOrDefault(texto(chave), Map.of());
            var lidos = new ArrayList<byte[]>();
            for (var campo : campos) {
                lidos.add(hash.get(texto(campo)));
            }
            return responder(lidos);
        }

        @Override
        public CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos) {
            ttls.add(ttlMilissegundos);
            valores.put(texto(chave), valor);
            return responder(Boolean.TRUE);
        }

        @Override
        public CompletableFuture<?> del(byte[] chave) {
            hashes.remove(texto(chave));
            valores.remove(texto(chave));
            return CompletableFuture.completedFuture(1L);
        }

        @Override
        public CompletableFuture<Long> executar(ScriptValkey script, byte[] chave, byte[]... argumentos) {
            var textos = new ArrayList<String>();
            for (var argumento : argumentos) {
                textos.add(texto(argumento));
            }
            scripts.add(new ScriptExecutado(texto(chave), textos));
            return responder(1L);
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
