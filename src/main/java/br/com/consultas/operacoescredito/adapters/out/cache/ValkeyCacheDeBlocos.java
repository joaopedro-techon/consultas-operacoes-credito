package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.domain.Bloco;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cache por bloco no Valkey: chave {@code op:{id_operacao}:{bloco}}, valor = {@code texto_detalhe} GZIP como veio
 * do DynamoDB, com TTL. Uma leitura é um {@code MGET} de todas as chaves; uma escrita é um {@code EVAL} com um
 * {@code SET ... PX} por bloco. Fail-open: toda chamada passa pelo circuit breaker {@value CacheConfig#CIRCUIT_BREAKER}
 * e pelo timeout curto do cliente ({@code spring.data.redis.timeout}); falha ou circuito aberto viram
 * "nada em cache" e a consulta segue no DynamoDB.
 * <p>
 * Cada chamada é uma observação {@code consultas.dependencia} ({@code dependencia=valkey},
 * {@code operacao=MGET|SET}); hits e misses por bloco vão para o contador {@code consultas.cache}
 * ({@code bloco}, {@code resultado=hit|miss}).
 */
class ValkeyCacheDeBlocos implements CacheDeBlocos {

    static final String DEPENDENCIA = "valkey";
    static final String METRICA_CACHE = "consultas.cache";

    private static final Logger log = LoggerFactory.getLogger(ValkeyCacheDeBlocos.class);
    /** KEYS = chaves; ARGV = valores na mesma ordem e, por último, o TTL em milissegundos. */
    private static final byte[] SCRIPT_GRAVAR = """
            local ttl = ARGV[#ARGV]
            for i, chave in ipairs(KEYS) do
              redis.call('SET', chave, ARGV[i], 'PX', ttl)
            end
            return #KEYS
            """.getBytes(StandardCharsets.UTF_8);

    private final RedisConnectionFactory conexoes;
    private final CircuitBreaker circuitBreaker;
    private final ObservationRegistry observationRegistry;
    private final byte[] ttlEmMilissegundos;
    private final Map<Bloco, Counter> hits;
    private final Map<Bloco, Counter> misses;

    ValkeyCacheDeBlocos(RedisConnectionFactory conexoes, CircuitBreaker circuitBreaker,
                        ObservationRegistry observationRegistry, MeterRegistry meterRegistry,
                        CacheProperties properties) {
        this.conexoes = conexoes;
        this.circuitBreaker = circuitBreaker;
        this.observationRegistry = observationRegistry;
        this.ttlEmMilissegundos = Long.toString(properties.ttl().toMillis()).getBytes(StandardCharsets.UTF_8);
        this.hits = contadores(meterRegistry, "hit");
        this.misses = contadores(meterRegistry, "miss");
    }

    @Override
    public Map<ChaveDeBloco, byte[]> buscar(Collection<ChaveDeBloco> chaves) {
        if (chaves.isEmpty()) {
            return Map.of();
        }
        var ordenadas = List.copyOf(chaves);
        var valores = executar("MGET", () -> mget(ordenadas)).orElse(List.of());
        var encontrados = new HashMap<ChaveDeBloco, byte[]>();
        for (int i = 0; i < ordenadas.size(); i++) {
            var chave = ordenadas.get(i);
            var valor = i < valores.size() ? valores.get(i) : null;
            if (valor != null) {
                encontrados.put(chave, valor);
                hits.get(chave.bloco()).increment();
            } else {
                misses.get(chave.bloco()).increment();
            }
        }
        return encontrados;
    }

    @Override
    public void gravar(Map<ChaveDeBloco, byte[]> blocos) {
        if (blocos.isEmpty()) {
            return;
        }
        executar("SET", () -> {
            set(blocos);
            return Boolean.TRUE;
        });
    }

    private List<byte[]> mget(List<ChaveDeBloco> chaves) {
        var bytes = chaves.stream().map(ChaveDeBloco::bytes).toArray(byte[][]::new);
        try (var conexao = conexoes.getConnection()) {
            var valores = conexao.stringCommands().mGet(bytes);
            return valores == null ? List.of() : valores;
        }
    }

    /**
     * Um {@code EVAL} com um {@code SET ... PX} por bloco: uma ida ao Valkey pela conexão compartilhada
     * (pipeline no Spring Data Redis abriria uma conexão dedicada por escrita).
     */
    private void set(Map<ChaveDeBloco, byte[]> blocos) {
        var chavesEValores = new byte[blocos.size() * 2 + 1][];
        int i = 0;
        for (var bloco : blocos.entrySet()) {
            chavesEValores[i] = bloco.getKey().bytes();
            chavesEValores[blocos.size() + i] = bloco.getValue();
            i++;
        }
        chavesEValores[chavesEValores.length - 1] = ttlEmMilissegundos;
        try (var conexao = conexoes.getConnection()) {
            conexao.scriptingCommands().eval(SCRIPT_GRAVAR, ReturnType.INTEGER, blocos.size(), chavesEValores);
        }
    }

    /** Executa a chamada sob o circuit breaker; qualquer falha vira {@code Optional.empty()} (fail-open). */
    private <T> Optional<T> executar(String operacao, Supplier<T> chamada) {
        try {
            return Optional.ofNullable(circuitBreaker.executeSupplier(() ->
                    Observacoes.dependencia(observationRegistry, DEPENDENCIA, operacao).observe(chamada)));
        } catch (CallNotPermittedException e) {
            log.debug("Circuit breaker do Valkey aberto; {} ignorado, consulta segue no DynamoDB", operacao);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Falha no Valkey ({}); consulta segue no DynamoDB: {}", operacao, e.toString());
            return Optional.empty();
        }
    }

    private static Map<Bloco, Counter> contadores(MeterRegistry registry, String resultado) {
        var contadores = new EnumMap<Bloco, Counter>(Bloco.class);
        for (var bloco : Bloco.values()) {
            contadores.put(bloco, Counter.builder(METRICA_CACHE)
                    .description("Blocos procurados no cache da visão consolidada")
                    .tag("bloco", bloco.nome())
                    .tag("resultado", resultado)
                    .register(registry));
        }
        return contadores;
    }
}
