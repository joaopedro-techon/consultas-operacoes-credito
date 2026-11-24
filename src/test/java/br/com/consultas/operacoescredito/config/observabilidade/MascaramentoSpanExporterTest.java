package br.com.consultas.operacoescredito.config.observabilidade;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MascaramentoSpanExporterTest {

    private static final String CPF = "52998224725";
    private static final String CONTA = "434115000065874";
    private static final String URL_CLIENTE = "http://servico/clientes/" + CPF + "/operacoes";
    private static final String URL_CONTA = "http://servico/contas/" + CONTA + "/operacoes";

    private final InMemorySpanExporter destino = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new MascaramentoSpanExporter(destino)))
            .build();

    @AfterEach
    void encerrar() {
        tracerProvider.close();
    }

    @Test
    void excecaoEStatusDoSpanSaemMascarados() {
        var span = tracerProvider.get("teste").spanBuilder("GET " + URL_CLIENTE).startSpan();
        span.recordException(new IllegalStateException("I/O error on GET request for \"" + URL_CLIENTE + "\"",
                new RuntimeException("causa em " + URL_CONTA)));
        span.setStatus(StatusCode.ERROR, "Falha em " + URL_CONTA);
        span.end();

        var exportado = unico();
        assertThat(exportado.toString()).doesNotContain(CPF, CONTA);
        assertThat(exportado.getName()).isEqualTo("GET http://servico/clientes/529******25/operacoes");
        assertThat(exportado.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(exportado.getStatus().getDescription()).contains("/contas/***********5874/");
        var evento = exportado.getEvents().getFirst();
        assertThat(evento.getName()).isEqualTo("exception");
        assertThat(evento.getAttributes().get(AttributeKey.stringKey("exception.message")))
                .contains("/clientes/529******25/");
        assertThat(evento.getAttributes().get(AttributeKey.stringKey("exception.stacktrace")))
                .contains("IllegalStateException", "/contas/***********5874/");
        assertThat(evento.getAttributes().get(AttributeKey.stringKey("exception.type")))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void atributosTextuaisSaemMascaradosEOsDemaisIntactos() {
        var span = tracerProvider.get("teste").spanBuilder("consulta").startSpan();
        span.setAllAttributes(Attributes.builder()
                .put("cpf_cnpj", CPF)
                .put("conta", CONTA)
                .put("http.url", URL_CLIENTE)
                .put(AttributeKey.stringArrayKey("urls"), List.of(URL_CONTA))
                .put("id_operacao", "52998224725")
                .put("status", 504L)
                .build());
        span.addEvent("evento", Attributes.of(AttributeKey.stringKey("detalhe"), "cpf_cnpj=" + CPF));
        span.end();

        var exportado = unico();
        var atributos = exportado.getAttributes();
        assertThat(atributos.get(AttributeKey.stringKey("cpf_cnpj"))).isEqualTo("529******25");
        assertThat(atributos.get(AttributeKey.stringKey("conta"))).isEqualTo("***********5874");
        assertThat(atributos.get(AttributeKey.stringArrayKey("urls"))).singleElement().asString()
                .doesNotContain(CONTA);
        assertThat(atributos.get(AttributeKey.stringKey("id_operacao"))).as("não é dado pessoal").isEqualTo(CPF);
        assertThat(atributos.get(AttributeKey.longKey("status"))).isEqualTo(504L);
        assertThat(exportado.getEvents().getFirst().getAttributes().get(AttributeKey.stringKey("detalhe")))
                .isEqualTo("cpf_cnpj=529******25");
        assertThat(exportado.getTraceId()).isNotBlank();
    }

    @Test
    void spanSemDadoPessoalSaiIgual() {
        var span = tracerProvider.get("teste").spanBuilder("servico operacao").startSpan();
        span.setAttribute("dependencia", "servico");
        span.setStatus(StatusCode.OK);
        span.end();

        var exportado = unico();
        assertThat(exportado.getName()).isEqualTo("servico operacao");
        assertThat(exportado.getAttributes().get(AttributeKey.stringKey("dependencia"))).isEqualTo("servico");
        assertThat(exportado.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    private SpanData unico() {
        assertThat(destino.getFinishedSpanItems()).hasSize(1);
        return destino.getFinishedSpanItems().getFirst();
    }
}
