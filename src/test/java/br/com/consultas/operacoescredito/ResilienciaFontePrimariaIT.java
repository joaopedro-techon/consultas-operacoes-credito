package br.com.consultas.operacoescredito;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resiliência da fonte primária contra o WireMock com atrasos, falhas e cenários: retry só do Resilience4j e só
 * para falhas retentáveis e com prazo, prazo da requisição por tipo de consulta (504), circuit breaker abrindo
 * (503 + {@code Retry-After}) e voltando pelo meio-aberto, e bulkhead cheio (503). Contexto próprio, com prazos
 * e limites pequenos; o timeout de resposta do pool é alto para que quem corta a espera seja o prazo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.prazo.operacao=1s",
        "consultas.prazo.cliente=1500ms",
        "consultas.prazo.conta=1500ms",
        "consultas.fonte-primaria.retry.prazo-minimo-restante=300ms",
        "consultas.fonte-primaria.pool.timeout-resposta=5s",
        "resilience4j.circuitbreaker.instances.fontePrimaria.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.fontePrimaria.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.fontePrimaria.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.fontePrimaria.permitted-number-of-calls-in-half-open-state=2",
        "resilience4j.bulkhead.instances.fontePrimaria.max-concurrent-calls=" + ResilienciaFontePrimariaIT.BULKHEAD,
        "resilience4j.bulkhead.instances.fontePrimaria.max-wait-duration=0ms"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class ResilienciaFontePrimariaIT {

    static final int BULKHEAD = 6;

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final GatewayMainframeSimulado gateway;
    private final CircuitBreakerRegistry circuitos;

    @Autowired
    ResilienciaFontePrimariaIT(@LocalServerPort int porta, @Qualifier("wireMock") GenericContainer<?> wireMock,
                               CircuitBreakerRegistry circuitos) {
        // Cliente sem retry automático: o do Apache HttpClient repetiria o 503 depois do Retry-After
        this.client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta)
                .build();
        this.gateway = new GatewayMainframeSimulado(wireMock);
        this.circuitos = circuitos;
    }

    @BeforeEach
    void circuitoFechado() {
        // Cada teste começa com o circuito fechado e a janela vazia, qualquer que seja a ordem de execução
        circuitos.circuitBreaker("fontePrimaria").reset();
    }

    @Test
    void falha5xxPassageiraERecuperadaPeloRetry() {
        var id = 920000001L;
        gateway.registrarOperacao(id, 0);
        var parcelas = "/mainframe/operacoes/" + id + "/blocos/parcelas";
        gateway.registrarPassoDoCenario("retry-" + id, "Started", "falhou", parcelas, 500, "erro");
        gateway.registrarPassoDoCenario("retry-" + id, "falhou", null, parcelas, 200, "000");
        var retriesAntes = metrica("resilience4j.retry.calls", "name:fontePrimaria", "kind:successful_with_retry");

        consultarOperacao(id, "parcelas")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].operacao.id_operacao").isEqualTo(id)
                .jsonPath("$.data[0].parcelas").isArray();

        assertThat(gateway.contar(parcelas)).as("tentativas no bloco que falhou uma vez").isEqualTo(2);
        assertThat(metrica("resilience4j.retry.calls", "name:fontePrimaria", "kind:successful_with_retry"))
                .isEqualTo(retriesAntes + 1);
    }

    @Test
    void falha5xxPersistenteTemSoDuasTentativasExtrasEDevolve502() {
        var id = 920000002L;
        gateway.registrarOperacao(id, 0);
        var parcelas = "/mainframe/operacoes/" + id + "/blocos/parcelas";
        gateway.registrar(parcelas, 503, "fora do ar", 0);

        consultarOperacao(id, "parcelas")
                .expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(gateway.contar(parcelas)).as("1 tentativa + 2 extras").isEqualTo(3);
    }

    @Test
    void erro4xxDoGatewayNaoERetentado() {
        var id = 920000003L;
        gateway.registrarOperacao(id, 0);
        var parcelas = "/mainframe/operacoes/" + id + "/blocos/parcelas";
        gateway.registrar(parcelas, 400, "requisicao invalida", 0);

        consultarOperacao(id, "parcelas").expectStatus().isEqualTo(502);

        assertThat(gateway.contar(parcelas)).isEqualTo(1);
    }

    @Test
    void semPrazoParaOutraTentativaNaoHaRetry() {
        var id = 920000004L;
        gateway.registrarOperacao(id, 0);
        var parcelas = "/mainframe/operacoes/" + id + "/blocos/parcelas";
        // Falha depois de 800 ms: dos 1 s de prazo restam menos que os 300 ms mínimos para tentar de novo
        gateway.registrar(parcelas, 500, "erro lento", 800);

        consultarOperacao(id, "parcelas").expectStatus().isEqualTo(502);

        assertThat(gateway.contar(parcelas)).isEqualTo(1);
    }

    @Test
    void prazoDaConsultaOperacaoEstouradoDevolve504SemEsperarOMainframeNemRetentar() {
        var id = 920000005L;
        gateway.registrarOperacao(id, 0);
        var parcelas = "/mainframe/operacoes/" + id + "/blocos/parcelas";
        gateway.registrar(parcelas, 200, "000", 4000);

        var inicio = System.nanoTime();
        consultarOperacao(id, "parcelas")
                .expectStatus().isEqualTo(504)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.correlation_id").exists()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("limite_operacoes", "expand", "OPERACAO"));
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("504 no prazo de 1 s da OPERACAO, não no atraso de 4 s").isBetween(900L, 2500L);
        assertThat(gateway.contar(parcelas)).as("timeout de resposta não é retentado").isEqualTo(1);
    }

    @Test
    void prazoDaConsultaClienteValeTambemParaAListaDeOperacoes() {
        gateway.registrar("/mainframe/clientes/70000000744/operacoes", 200, "000", 4000);

        var inicio = System.nanoTime();
        client.get().uri(ROTA + "?cpf_cnpj=70000000744")
                .header("x-tipo-consulta", "CLIENTE")
                .exchange()
                .expectStatus().isEqualTo(504);
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("504 no prazo de 1,5 s do CLIENTE").isBetween(1400L, 3000L);
    }

    @Test
    void circuitBreakerAbreComFalhasDoMainframeEDevolve503ComRetryAfterSemChamarOGateway()
            throws InterruptedException {
        var id = 920000070L;
        var blocos = "/mainframe/operacoes/" + id + "/blocos/.*";
        gateway.registrarPadrao(blocos, 500, "erro", 0);

        abrirCircuito(id);
        Thread.sleep(200); // o WireMock registra a requisição no journal depois de responder
        var chamadasComCircuitoAberto = gateway.contar(blocos);

        consultarOperacao(id, null)
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.title").isEqualTo("Fonte de dados temporariamente indisponível")
                .jsonPath("$.correlation_id").exists();

        assertThat(gateway.contar(blocos)).as("circuito aberto não chama o gateway nem retenta")
                .isEqualTo(chamadasComCircuitoAberto);
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:fontePrimaria", "state:open")).isEqualTo(1.0);
    }

    @Test
    void circuitoMeioAbertoFechaQuandoOMainframeVolta() throws InterruptedException {
        var id = 920000080L;
        var falhas = gateway.registrarPadrao("/mainframe/operacoes/" + id + "/blocos/.*", 500, "erro", 0);
        abrirCircuito(id);

        gateway.remover(falhas);
        gateway.registrarOperacao(id, 0);
        Thread.sleep(1200); // wait-duration-in-open-state = 1 s

        // Meio-aberto: as 2 chamadas permitidas (operacao + originacao) dão certo e o circuito fecha
        consultarOperacao(id, null).expectStatus().isOk();
        consultarOperacao(id, null).expectStatus().isOk();
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:fontePrimaria", "state:closed")).isEqualTo(1.0);
    }

    @Test
    void bulkheadCheioDevolve503ComRetryAfter() {
        var id = 920000090L;
        gateway.registrarPadrao("/mainframe/operacoes/" + id + "/blocos/.*", 500, "lento", 500);

        // 9 blocos em paralelo (teto do fan-out 16) contra um bulkhead de 6 chamadas sem espera
        consultarOperacao(id, "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof")
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Fonte de dados temporariamente indisponível");
    }

    @Test
    void metricasDeResilienciaTemSoTagsDeBaixaCardinalidade() {
        var id = 920000100L;
        gateway.registrarOperacao(id, 0);
        consultarOperacao(id, null).expectStatus().isOk();

        for (var nome : new String[] {"resilience4j.circuitbreaker.state", "resilience4j.circuitbreaker.calls",
                "resilience4j.retry.calls", "resilience4j.bulkhead.available.concurrent.calls",
                "resilience4j.ratelimiter.available.permissions"}) {
            var tags = new ArrayList<String>();
            corpoDaMetrica(nome).path("availableTags").forEach(tag -> tags.add(tag.path("tag").asString()));
            assertThat(tags).as(nome).isSubsetOf("name", "kind", "state");
            assertThat(corpoDaMetrica(nome).path("availableTags").toString()).doesNotContain(Long.toString(id));
        }
    }

    /** Falhas seguidas até o circuito abrir (janela de 10 chamadas, 50% de falhas). */
    private void abrirCircuito(long id) {
        var status = 0;
        for (int i = 0; i < 10 && status != 503; i++) {
            status = client.get().uri(ROTA + "?id_operacao=" + id)
                    .header("x-tipo-consulta", "OPERACAO")
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
        }
        assertThat(status).as("circuito aberto depois de falhas seguidas").isEqualTo(503);
    }

    private RestTestClient.ResponseSpec consultarOperacao(long id, String expand) {
        var uri = ROTA + "?id_operacao=" + id + (expand == null ? "" : "&expand=" + expand);
        return client.get().uri(uri)
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", "resiliencia-" + UUID.randomUUID())
                .exchange();
    }

    private double metrica(String nome, String... tags) {
        var corpo = corpoDaMetrica(nome, tags);
        for (var medida : corpo.path("measurements")) {
            return medida.path("value").asDouble();
        }
        return 0.0;
    }

    private JsonNode corpoDaMetrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome);
        for (int i = 0; i < tags.length; i++) {
            uri.append(i == 0 ? "?" : "&").append("tag=").append(tags[i]);
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(resultado.getResponseBody());
    }
}
