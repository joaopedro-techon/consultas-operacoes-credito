package br.com.consultas.operacoescredito;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
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
@ExtendWith(OutputCaptureExtension.class)
class TracesIT {

    private static final String ROTA = "/v1/operacoes-credito";
    private static final String CPF = "52998224725";
    private static final String CONTA = "434115000065874";

    private final RestTestClient client;
    private final InMemorySpanExporter exportador;
    private final GatewayMainframeSimulado gateway;

    @LocalServerPort
    private int porta;

    @Autowired
    TracesIT(RestTestClient client, InMemorySpanExporter exportador,
             @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.exportador = exportador;
        this.gateway = new GatewayMainframeSimulado(wireMock);
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
    void fontePrimariaPorClienteGeraEtapaDaListaEFanOutComUmSpanPorOperacaoEBloco() {
        var correlationId = novoCorrelationId();

        client.get().uri(ROTA + "/fonte-primaria?cpf_cnpj=" + CPF + "&expand=parcelas&limite_operacoes=2")
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isOk();

        var http = spanHttp(correlationId);
        var spans = spansDoTrace(http.getTraceId());
        var lista = unico(spans, "etapa", "lista_operacoes");
        var fanOut = unico(spans, "etapa", "fan_out");
        assertThat(lista.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(fanOut.getParentSpanId()).isEqualTo(http.getSpanId());
        assertThat(atributo(fanOut, "operacoes")).isEqualTo("2");

        var chamadaDaLista = unico(spans, "operacao", "lista_operacoes_cliente");
        assertThat(chamadaDaLista.getName()).isEqualTo("fonte_primaria lista_operacoes_cliente");
        assertThat(chamadaDaLista.getParentSpanId()).isEqualTo(lista.getSpanId());
        var chamadasDeBloco = spans.stream()
                .filter(span -> "fonte_primaria".equals(atributo(span, "dependencia")))
                .filter(span -> span != chamadaDaLista)
                .toList();
        assertThat(chamadasDeBloco).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(fanOut.getSpanId()));
        assertThat(chamadasDeBloco).extracting(span -> atributo(span, "operacao"))
                .containsExactlyInAnyOrder("operacao", "originacao", "parcelas", "operacao", "originacao", "parcelas");
    }

    @Test
    void cpfCnpjEContaNaRotaDoGatewaySaemMascaradosNosSpansDaFontePrimaria() {
        var porCliente = novoCorrelationId();
        var porConta = novoCorrelationId();

        client.get().uri(ROTA + "/fonte-primaria?cpf_cnpj=" + CPF + "&limite_operacoes=1")
                .header("x-tipo-consulta", "CLIENTE")
                .header("x-correlationid", porCliente)
                .exchange()
                .expectStatus().isOk();
        client.get().uri(ROTA + "/fonte-primaria?conta=" + CONTA + "&limite_operacoes=1")
                .header("x-tipo-consulta", "CONTA")
                .header("x-correlationid", porConta)
                .exchange()
                .expectStatus().isOk();

        var spans = new java.util.ArrayList<SpanData>(spansDoTrace(spanHttp(porCliente).getTraceId()));
        spans.addAll(spansDoTrace(spanHttp(porConta).getTraceId()));
        assertThat(spans).anySatisfy(span -> assertThat(span.getName()).contains("lista_operacoes_conta"));
        assertThat(spans.toString()).doesNotContain(CPF, CONTA);
    }

    /**
     * Gateway fora (5xx) ou lento (timeout de resposta) nas listas de cliente e de conta: a exceção do
     * {@code RestClient} traz a URL do gateway com o CPF/CNPJ ou a conta na mensagem e no stack trace, que vão
     * para os eventos de exceção e o status dos spans (do adapter, do cliente HTTP e do servidor) e para o log de
     * erro. Nada disso pode sair em claro.
     */
    @Test
    void falhaETimeoutDoGatewayNaoDeixamCpfCnpjNemContaNosSpansNemNosLogs(CapturedOutput saida) {
        var cpfForaDoAr = "11144477735";
        var cpfLento = "39053344705";
        var contaForaDoAr = "998877665544332";
        var contaLenta = "112233445566778";
        var stubs = List.of(
                gateway.registrar("/mainframe/clientes/" + cpfForaDoAr + "/operacoes", 503, "fora do ar", 0),
                gateway.registrar("/mainframe/clientes/" + cpfLento + "/operacoes", 200, "000", 2000),
                gateway.registrar("/mainframe/contas/" + contaForaDoAr + "/operacoes", 500, "erro", 0),
                gateway.registrar("/mainframe/contas/" + contaLenta + "/operacoes", 200, "000", 2000));
        var correlationIds = new ArrayList<String>();
        try {
            correlationIds.add(consultarFontePrimaria("CLIENTE", "cpf_cnpj=" + cpfForaDoAr, 502));
            correlationIds.add(consultarFontePrimaria("CLIENTE", "cpf_cnpj=" + cpfLento, 504));
            correlationIds.add(consultarFontePrimaria("CONTA", "conta=" + contaForaDoAr, 502));
            correlationIds.add(consultarFontePrimaria("CONTA", "conta=" + contaLenta, 504));
        } finally {
            stubs.forEach(gateway::remover);
        }

        var spans = new ArrayList<SpanData>();
        for (var correlationId : correlationIds) {
            spans.addAll(spansDoTrace(spanHttp(correlationId).getTraceId()));
        }
        assertThat(spans).as("o erro do gateway foi registrado nos spans")
                .anySatisfy(span -> assertThat(span.getEvents()).isNotEmpty())
                .anySatisfy(span -> assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR));
        var exportados = spans.toString();
        assertThat(exportados).as("eventos de exceção mascarados, não omitidos").contains("/clientes/111******35");
        assertThat(exportados).doesNotContain(cpfForaDoAr, cpfLento, contaForaDoAr, contaLenta);
        assertThat(exportador.getFinishedSpanItems().toString())
                .doesNotContain(cpfForaDoAr, cpfLento, contaForaDoAr, contaLenta);

        // O log de acesso sai depois do log de erro da mesma requisição (mesmo appender assíncrono)
        await().atMost(Duration.ofSeconds(5)).until(() -> correlationIds.stream()
                .allMatch(correlationId -> saida.getOut().contains(correlationId)));
        assertThat(saida.getOut()).contains("Falha na fonte de operações");
        assertThat(saida.getAll()).doesNotContain(cpfForaDoAr, cpfLento, contaForaDoAr, contaLenta);
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

    private String consultarFontePrimaria(String tipo, String criterio, int status) {
        var correlationId = novoCorrelationId();
        client.get().uri(ROTA + "/fonte-primaria?" + criterio)
                .header("x-tipo-consulta", tipo)
                .header("x-correlationid", correlationId)
                .exchange()
                .expectStatus().isEqualTo(status);
        return correlationId;
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
