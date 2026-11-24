package br.com.consultas.operacoescredito;

import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Hit e miss de cache por bloco no log de acesso (seção 9 da especificação), com o cache ligado
 * ({@link CacheContainers}): a primeira consulta só tem misses; a segunda acha no cache o que a primeira gravou.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({VisaoConsolidadaContainers.class, CacheContainers.class})
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogDeAcessoComCacheIT {

    private static final String ROTA = "/v1/operacoes-credito";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final RedisClient valkey;

    @Autowired
    LogDeAcessoComCacheIT(RestTestClient client, @Qualifier("valkey") GenericContainer<?> valkey) {
        this.client = client;
        this.valkey = RedisClient.create(CacheContainers.uriDireta(valkey));
    }

    @AfterAll
    void fechar() {
        valkey.shutdown();
    }

    @Test
    void logDeAcessoTrazHitsEMissesDeCachePorBloco(CapturedOutput saida) {
        try (var conexao = valkey.connect()) {
            conexao.sync().flushall();
        }
        var primeira = "teste-" + UUID.randomUUID();
        var segunda = "teste-" + UUID.randomUUID();

        consultar(primeira, "parcelas");
        // A gravação no cache acontece depois da resposta montada; espera o bloco aparecer no Valkey
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            try (var conexao = valkey.connect()) {
                return conexao.sync().exists("op:100000001:parcelas") == 1;
            }
        });
        consultar(segunda, "parcelas,saldo");

        var antes = logDeAcesso(saida, primeira);
        assertThat(contadores(antes, "cache_hits")).isEmpty();
        assertThat(contadores(antes, "cache_misses"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("operacao", 1L, "originacao", 1L, "parcelas", 1L));
        var depois = logDeAcesso(saida, segunda);
        assertThat(contadores(depois, "cache_hits"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("operacao", 1L, "originacao", 1L, "parcelas", 1L));
        assertThat(contadores(depois, "cache_misses")).containsExactlyInAnyOrderEntriesOf(Map.of("saldo", 1L));
    }

    private void consultar(String correlationId, String expand) {
        client.get().uri(ROTA + "?id_operacao=100000001&expand=" + expand)
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();
    }

    private static Map<String, Long> contadores(JsonNode log, String campo) {
        assertThat(log.path(campo).isObject()).as(campo).isTrue();
        var contadores = new LinkedHashMap<String, Long>();
        log.path(campo).properties().forEach(par -> contadores.put(par.getKey(), par.getValue().asLong()));
        return contadores;
    }

    /** Espera o log de acesso da requisição: o appender é assíncrono. */
    private static JsonNode logDeAcesso(CapturedOutput saida, String correlationId) {
        return await().atMost(Duration.ofSeconds(5))
                .until(() -> saida.getOut().lines()
                        .filter(linha -> linha.startsWith("{"))
                        .map(JSON::readTree)
                        .filter(log -> "acesso".equals(log.path("log").path("logger").asString(null))
                                || "acesso".equals(log.path("log.logger").asString(null)))
                        .filter(log -> correlationId.equals(log.path("correlation_id").asString(null)))
                        .findFirst(), Optional::isPresent)
                .orElseThrow();
    }
}
