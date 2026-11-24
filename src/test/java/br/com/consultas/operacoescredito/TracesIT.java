package br.com.consultas.operacoescredito;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spans exportados depois de consultas reais (exportador em memória no lugar do OTLP): span HTTP com o
 * contexto da requisição, span filho por chamada ao DynamoDB, {@code traceparent} W3C respeitado e
 * CPF/CNPJ e conta sempre mascarados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.opentelemetry.tracing.export.schedule-delay=50ms")
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import({VisaoConsolidadaContainers.class, FontePrimariaContainers.class, TracesIT.ExportadorEmMemoria.class})
class TracesIT {

    private static final String ROTA = "/v1/operacoes-credito";
    private static final String CPF = "52998224725";
    private static final String CONTA = "434115000065874";

    private final RestTestClient client;
    private final InMemorySpanExporter exportador;

    @LocalServerPort
    private int porta;

    @Autowired
    TracesIT(RestTestClient client, InMemorySpanExporter exportador) {
        this.client = client;
        this.exportador = exportador;
    }

    @Test
    void consultaGeraSpanHttpComContextoESpanFilhoDoDynamoDb() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-consumidor", "sistema-custodia")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var http = spanHttp(correlationId);
        assertThat(atributo(http, "consumidor")).isEqualTo("sistema-custodia");
        assertThat(atributo(http, "tipo_consulta")).isEqualTo("OPERACAO");
        assertThat(atributo(http, "rota")).isEqualTo("visao_consolidada");
        assertThat(atributo(http, "id_operacao")).isEqualTo("100000001");

        var dynamoDb = spansDoTrace(http.getTraceId()).stream()
                .filter(span -> "dynamodb".equals(atributo(span, "dependencia")))
                .toList();
        assertThat(dynamoDb).isNotEmpty().allSatisfy(span -> {
            assertThat(span.getName()).isEqualTo("dynamodb BatchGetItem");
            assertThat(span.getParentSpanId()).isEqualTo(http.getSpanId());
        });
    }

    @Test
    void consultaPorClienteGeraEtapasDeGsiEBatchComAsChamadasAoDynamoDbDentro() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF + "&expand=parcelas")
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var http = spanHttp(correlationId);
        var spans = spansDoTrace(http.getTraceId());
        var gsi = unico(spans, "etapa", "consulta_gsi");
        var batch = unico(spans, "etapa", "batch");
        assertThat(gsi.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(batch.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(atributo(gsi, "indice")).isEqualTo("gsi-cliente");
        assertThat(spans).filteredOn(span -> "dynamodb Query".equals(span.getName()))
                .isNotEmpty().allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(gsi.getSpanId()));
        assertThat(spans).filteredOn(span -> "dynamodb BatchGetItem".equals(span.getName()))
                .isNotEmpty().allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(batch.getSpanId()));
    }

    @Test
    void filtroGeraEtapaPropria() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "?id_operacao=100000001&expand=parcelas&filter={filter}",
                        "parcelas.numero_parcela_amortizacao==3")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var http = spanHttp(correlationId);
        var filtro = unico(spansDoTrace(http.getTraceId()), "etapa", "filtro");
        assertThat(filtro.getParentSpanId()).isEqualTo(http.getSpanId());
    }

    @Test
    void fontePrimariaGeraFanOutComUmSpanDeDependenciaPorBloco() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "/fonte-primaria?id_operacao=100000001&expand=parcelas,saldo")
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var http = spanHttp(correlationId);
        assertThat(atributo(http, "rota")).isEqualTo("fonte_primaria");
        var spans = spansDoTrace(http.getTraceId());
        var fanOut = unico(spans, "etapa", "fan_out");
        assertThat(fanOut.getParentSpanId()).isEqualTo(http.getSpanId());
        var chamadas = spans.stream().filter(span -> "fonte_primaria".equals(atributo(span, "dependencia"))).toList();
        assertThat(chamadas).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(fanOut.getSpanId()));
        assertThat(chamadas).extracting(span -> atributo(span, "operacao"))
                .containsExactlyInAnyOrder("operacao", "originacao", "parcelas", "saldo");
    }

    @Test
    void traceparentRecebidoContinuaOTrace() throws Exception {
        var correlationId = novoCorrelationId();
        var traceId = UUID.randomUUID().toString().replace("-", "");

        // HttpClient puro: o RestTestClient é instrumentado e sobrescreveria o traceparent com o próprio trace
        var requisicao = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + ROTA + "?id_operacao=100000001"))
                .header("x-tipo-consulta", "OPERACAO")
                .header("x-correlationid", correlationId)
                .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                .build();
        try (var http = HttpClient.newHttpClient()) {
            assertThat(http.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        }

        assertThat(spanHttp(correlationId).getTraceId()).isEqualTo(traceId);
    }

    @Test
    void cpfCnpjEContaSaemMascaradosNosSpans() {
        var porCliente = novoCorrelationId();
        var porConta = novoCorrelationId();

        client.get().uri(ROTA + "?cpf_cnpj=" + CPF)
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", porCliente)
                .exchange();
        client.get().uri(ROTA + "?conta=" + CONTA)
                .header("x-tipo-consulta", "CONTA")
                .header("x-correlationid", porConta)
                .exchange();

        assertThat(atributo(spanHttp(porCliente), "cpf_cnpj")).isEqualTo("529******25");
        assertThat(atributo(spanHttp(porConta), "conta")).isEqualTo("***********5874");
        assertThat(exportador.getFinishedSpanItems().toString()).doesNotContain(CPF, CONTA);
    }

    private SpanData spanHttp(String correlationId) {
        return await().atMost(Duration.ofSeconds(10)).until(
                () -> exportador.getFinishedSpanItems().stream()
                        .filter(span -> correlationId.equals(atributo(span, "correlation_id")))
                        .findFirst(),
                java.util.Optional::isPresent).orElseThrow();
    }

    private List<SpanData> spansDoTrace(String traceId) {
        return exportador.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }

    private static SpanData unico(List<SpanData> spans, String atributo, String valor) {
        var encontrados = spans.stream().filter(span -> valor.equals(atributo(span, atributo))).toList();
        assertThat(encontrados).as("span com %s=%s", atributo, valor).hasSize(1);
        return encontrados.getFirst();
    }

    private static String atributo(SpanData span, String nome) {
        return span.getAttributes().get(AttributeKey.stringKey(nome));
    }

    private static String novoCorrelationId() {
        return "traces-" + UUID.randomUUID();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExportadorEmMemoria {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
