package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resiliência da visão consolidada observada pela API HTTP, com o DynamoDB (LocalStack) atrás de um Toxiproxy
 * que o teste atrasa ou derruba: prazo por tipo de consulta (504), circuit breaker e rate limiter (503 com
 * {@code Retry-After}) e bulkhead de CLIENTE/CONTA isolado de OPERACAO.
 * <p>
 * Prazos maiores que os de produção (150/400 ms) para não depender da velocidade da máquina: o que se verifica
 * é que o prazo configurado para o tipo de consulta é o que vale. Sem cache (default dos testes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.prazo.operacao=" + ResilienciaVisaoConsolidadaIT.PRAZO_OPERACAO_MS + "ms",
        "consultas.prazo.cliente=" + ResilienciaVisaoConsolidadaIT.PRAZO_CLIENTE_CONTA_MS + "ms",
        "consultas.prazo.conta=" + ResilienciaVisaoConsolidadaIT.PRAZO_CLIENTE_CONTA_MS + "ms",
        // Só o prazo da consulta corta a chamada lenta (sem timeout de tentativa ou de socket antes dele)
        "consultas.dynamodb.timeout-tentativa=10s",
        "consultas.dynamodb.pool.timeout-resposta=10s",
        "resilience4j.circuitbreaker.instances.dynamodb.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.dynamodb.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.dynamodb.wait-duration-in-open-state="
                + ResilienciaVisaoConsolidadaIT.CIRCUITO_ABERTO_S + "s",
        "resilience4j.bulkhead.instances.dynamodb-cliente-conta.max-concurrent-calls="
                + ResilienciaVisaoConsolidadaIT.TETO_CLIENTE_CONTA,
        "resilience4j.ratelimiter.instances.dynamodb.limit-refresh-period=1s"})
@Import(VisaoConsolidadaComToxiproxyContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResilienciaVisaoConsolidadaIT {

    static final int PRAZO_OPERACAO_MS = 250;
    static final int PRAZO_CLIENTE_CONTA_MS = 1200;
    static final int CIRCUITO_ABERTO_S = 30;
    static final int TETO_CLIENTE_CONTA = 2;

    private static final String ROTA = "/v1/operacoes-credito";
    private static final String CLIENTE = "52998224725";
    private static final String CONTA = "434115000065874";
    /** Folga para agendamento e rede da máquina de teste além do prazo. */
    private static final long FOLGA_MS = 800;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final Proxy proxyDynamoDb;
    private final CircuitBreakerRegistry circuitBreakers;
    private final BulkheadRegistry bulkheads;
    private final RateLimiterRegistry rateLimiters;

    @Autowired
    ResilienciaVisaoConsolidadaIT(@LocalServerPort int porta, Proxy proxyDynamoDb,
                                  CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads,
                                  RateLimiterRegistry rateLimiters) {
        // Cliente sem retry automático: o do Apache HttpClient repetiria o 503 depois do Retry-After
        this.client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + porta)
                .build();
        this.proxyDynamoDb = proxyDynamoDb;
        this.circuitBreakers = circuitBreakers;
        this.bulkheads = bulkheads;
        this.rateLimiters = rateLimiters;
    }

    @BeforeAll
    void aquecer() {
        // Primeiras chamadas (classes, conexões, credenciais) fora das medições de tempo
        for (int i = 0; i < 3; i++) {
            consultar("OPERACAO", "id_operacao=100000001");
            consultar("CLIENTE", "cpf_cnpj=" + CLIENTE);
        }
    }

    @BeforeEach
    void dynamoDbSaudavel() throws IOException {
        for (var toxic : proxyDynamoDb.toxics().getAll()) {
            toxic.remove();
        }
        proxyDynamoDb.enable();
        circuitBreakers.circuitBreaker("dynamodb").reset();
    }

    @Test
    void dynamoDbLentoEmOperacaoDevolve504NoPrazoDeOperacaoComSugestoes() throws IOException {
        proxyDynamoDb.toxics().latency("dynamodb-lento", ToxicDirection.DOWNSTREAM, 2_500);

        var inicio = System.nanoTime();
        var resposta = consultar("OPERACAO", "id_operacao=100000001&expand=parcelas");
        var duracaoMs = desde(inicio);

        assertThat(resposta.getStatus().value()).isEqualTo(504);
        assertThat(resposta.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        var problema = JSON.readTree(resposta.getResponseBody());
        assertThat(problema.get("title").asString()).isEqualTo("Fonte de dados sem resposta no prazo");
        assertThat(problema.get("detail").asString()).contains("limite_operacoes", "expand", "OPERACAO");
        assertThat(problema.get("correlation_id").asString()).isNotBlank();
        assertThat(problema.has("data")).isFalse();
        assertThat(duracaoMs).as("504 no prazo de OPERACAO, sem esperar o DynamoDB")
                .isBetween((long) PRAZO_OPERACAO_MS, PRAZO_OPERACAO_MS + FOLGA_MS);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CLIENTE, cpf_cnpj=" + CLIENTE, "CONTA, conta=" + CONTA})
    void dynamoDbLentoEmClienteEContaDevolve504NoPrazoDoTipoComSugestoes(String tipo, String parametro)
            throws IOException {
        proxyDynamoDb.toxics().latency("dynamodb-lento", ToxicDirection.DOWNSTREAM, 5_000);

        var inicio = System.nanoTime();
        var resposta = consultar(tipo, parametro + "&expand=parcelas");
        var duracaoMs = desde(inicio);

        assertThat(resposta.getStatus().value()).isEqualTo(504);
        var problema = JSON.readTree(resposta.getResponseBody());
        assertThat(problema.get("status").asInt()).isEqualTo(504);
        assertThat(problema.get("detail").asString()).contains("limite_operacoes", "expand", "OPERACAO");
        assertThat(duracaoMs).as("504 no prazo de " + tipo)
                .isBetween((long) PRAZO_CLIENTE_CONTA_MS, PRAZO_CLIENTE_CONTA_MS + FOLGA_MS);
    }

    @Test
    void prazoValePorTipoDeConsulta() throws IOException {
        // Uma ida e volta lenta: passa do prazo de OPERACAO; as duas de CLIENTE (Query + BatchGetItem) cabem no dele
        proxyDynamoDb.toxics().latency("dynamodb-lento", ToxicDirection.DOWNSTREAM, 400);

        assertThat(consultar("OPERACAO", "id_operacao=100000001").getStatus().value()).isEqualTo(504);
        var cliente = consultar("CLIENTE", "cpf_cnpj=" + CLIENTE);
        assertThat(cliente.getStatus().value()).isEqualTo(200);
        assertThat(JSON.readTree(cliente.getResponseBody()).get("data")).hasSize(3);
    }

    @Test
    void comODynamoDbForaOCircuitBreakerAbreEDevolve503ComRetryAfterSemChamarODynamoDb() throws IOException {
        proxyDynamoDb.disable();

        var statusAntesDeAbrir = new ArrayList<Integer>();
        EntityExchangeResult<String> rejeitada = null;
        for (int i = 0; i < 10 && rejeitada == null; i++) {
            var resposta = consultar("OPERACAO", "id_operacao=100000001");
            if (resposta.getStatus().value() == 503) {
                rejeitada = resposta;
            } else {
                statusAntesDeAbrir.add(resposta.getStatus().value());
            }
        }

        // Falha da dependência (502, ou 504 se o prazo acabar antes) até o circuito abrir
        assertThat(statusAntesDeAbrir).isNotEmpty().allSatisfy(status -> assertThat(status).isIn(502, 504));
        assertThat(rejeitada).as("circuit breaker aberto").isNotNull();
        assertRetryAfterEntre(rejeitada, 1, CIRCUITO_ABERTO_S);
        var problema = JSON.readTree(rejeitada.getResponseBody());
        assertThat(problema.get("status").asInt()).isEqualTo(503);
        assertThat(problema.get("correlation_id").asString()).isNotBlank();
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:dynamodb", "state:open")).isEqualTo(1.0);

        // Aberto, nem tenta o DynamoDB: nenhuma chamada nova medida e resposta imediata
        var chamadasAoDynamoDb = metrica("consultas.dependencia", "dependencia:dynamodb");
        var inicio = System.nanoTime();
        assertThat(consultar("CLIENTE", "cpf_cnpj=" + CLIENTE).getStatus().value()).isEqualTo(503);
        assertThat(desde(inicio)).isLessThan(PRAZO_OPERACAO_MS);
        assertThat(metrica("consultas.dependencia", "dependencia:dynamodb")).isEqualTo(chamadasAoDynamoDb);
    }

    @Test
    void rateLimiterEsgotadoDevolve503ComRetryAfter() throws InterruptedException {
        var rateLimiter = rateLimiters.rateLimiter("dynamodb");
        var limiteOriginal = rateLimiter.getRateLimiterConfig().getLimitForPeriod();
        rateLimiter.changeLimitForPeriod(1);
        try {
            Thread.sleep(1_100); // o novo limite vale a partir do próximo período
            var rejeitadas = new ArrayList<EntityExchangeResult<String>>();
            for (int i = 0; i < 5; i++) {
                var resposta = consultar("OPERACAO", "id_operacao=100000001");
                assertThat(resposta.getStatus().value()).isIn(200, 503);
                if (resposta.getStatus().value() == 503) {
                    rejeitadas.add(resposta);
                }
            }
            assertThat(rejeitadas).isNotEmpty();
            rejeitadas.forEach(resposta -> assertRetryAfterEntre(resposta, 1, 1));
        } finally {
            rateLimiter.changeLimitForPeriod(limiteOriginal);
            Thread.sleep(1_100);
        }
    }

    @Test
    void clienteEContaSaturadosNaoImpedemOperacao() {
        var bulkhead = bulkheads.bulkhead("dynamodb-cliente-conta");
        var ocupadas = 0;
        try {
            // Consultas CLIENTE/CONTA em andamento ocupando todo o teto delas
            while (bulkhead.tryAcquirePermission()) {
                ocupadas++;
            }
            assertThat(ocupadas).isEqualTo(TETO_CLIENTE_CONTA);

            var cliente = consultar("CLIENTE", "cpf_cnpj=" + CLIENTE);
            assertThat(cliente.getStatus().value()).isEqualTo(503);
            assertRetryAfterEntre(cliente, 1, 1);
            assertThat(consultar("CONTA", "conta=" + CONTA).getStatus().value()).isEqualTo(503);
            assertThat(consultar("OPERACAO", "id_operacao=100000001").getStatus().value()).isEqualTo(200);
        } finally {
            for (int i = 0; i < ocupadas; i++) {
                bulkhead.onComplete();
            }
        }
        assertThat(consultar("CLIENTE", "cpf_cnpj=" + CLIENTE).getStatus().value()).isEqualTo(200);
    }

    @Test
    void consultasConcorrentesDeClienteAlemDoTetoSaoRejeitadasEnquantoOperacaoSegue() throws Exception {
        // DynamoDB lento: cada CLIENTE (Query + BatchGetItem) fica em andamento tempo bastante para lotar o teto;
        // a OPERACAO (uma ida e volta) cabe no prazo dela
        proxyDynamoDb.toxics().latency("dynamodb-lento", ToxicDirection.DOWNSTREAM, 120);
        var concorrentes = TETO_CLIENTE_CONTA * 3;
        var clientes = new ArrayList<Thread>();
        var statusClientes = new ConcurrentLinkedQueue<Integer>();
        for (int i = 0; i < concorrentes; i++) {
            clientes.add(Thread.ofVirtual().start(() ->
                    statusClientes.add(consultar("CLIENTE", "cpf_cnpj=" + CLIENTE).getStatus().value())));
        }
        Thread.sleep(50);
        var operacao = consultar("OPERACAO", "id_operacao=100000001");
        for (var thread : clientes) {
            thread.join(Duration.ofSeconds(10));
        }

        assertThat(operacao.getStatus().value()).isEqualTo(200);
        assertThat(statusClientes).hasSize(concorrentes).allSatisfy(status -> assertThat(status).isIn(200, 503));
        assertThat(statusClientes).filteredOn(status -> status == 503).isNotEmpty();
    }

    private EntityExchangeResult<String> consultar(String tipo, String query) {
        return client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipo)
                .exchange()
                .returnResult(String.class);
    }

    private static void assertRetryAfterEntre(EntityExchangeResult<String> resposta, int minimo, int maximo) {
        var retryAfter = resposta.getResponseHeaders().getFirst("Retry-After");
        assertThat(retryAfter).as("Retry-After").isNotNull().containsOnlyDigits();
        assertThat(Integer.parseInt(retryAfter)).isBetween(minimo, maximo);
    }

    private static long desde(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }

    /** Soma das primeiras medidas (COUNT ou VALUE) da métrica no Actuator; 0 se ela ainda não existe. */
    private double metrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome);
        for (int i = 0; i < tags.length; i++) {
            uri.append(i == 0 ? '?' : '&').append("tag=").append(tags[i]);
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return 0;
        }
        JsonNode medidas = JSON.readTree(resultado.getResponseBody()).get("measurements");
        return medidas.get(0).get("value").asDouble();
    }
}
