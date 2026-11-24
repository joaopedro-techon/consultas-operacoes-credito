package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiter interno da fonte primária: esgotadas as permissões do período, a aplicação recusa com 503 e
 * {@code Retry-After} sem chamar o mainframe. Contexto próprio (4 chamadas por minuto) com um único teste, para
 * que nenhum outro consuma as permissões.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.ratelimiter.instances.fontePrimaria.limit-for-period=4",
        "resilience4j.ratelimiter.instances.fontePrimaria.limit-refresh-period=1m",
        "resilience4j.ratelimiter.instances.fontePrimaria.timeout-duration=0s"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class RateLimiterFontePrimariaIT {

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";

    private final RestTestClient client;
    private final GatewayMainframeSimulado gateway;

    @Autowired
    RateLimiterFontePrimariaIT(@LocalServerPort int porta, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        // Cliente sem retry automático: o do Apache HttpClient repetiria o 503 depois do Retry-After
        this.client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta)
                .build();
        this.gateway = new GatewayMainframeSimulado(wireMock);
    }

    @Test
    void semPermissoesNoPeriodoDevolve503ComRetryAfterSemChamarOMainframe() {
        var id = 920000200L;
        gateway.registrarOperacao(id, 0);
        var blocos = "/mainframe/operacoes/" + id + "/blocos/.*";

        // Cada consulta sem expand faz 2 chamadas (operacao + originacao): as duas primeiras usam as 4 permissões
        consultar(id).expectStatus().isOk();
        consultar(id).expectStatus().isOk();
        consultar(id)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "60")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Fonte de dados temporariamente indisponível")
                .jsonPath("$.correlation_id").exists();

        assertThat(gateway.contar(blocos)).isEqualTo(4);
    }

    private RestTestClient.ResponseSpec consultar(long id) {
        return client.get().uri(ROTA + "?id_operacao=" + id)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
