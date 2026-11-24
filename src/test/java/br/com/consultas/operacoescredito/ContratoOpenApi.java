package br.com.consultas.operacoescredito;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.ExchangeResult;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida respostas da API contra o contrato escrito à mão, {@value #CAMINHO} (ADR-0017): status declarado,
 * content type, headers obrigatórios ({@code x-correlationid}, {@code Retry-After}) e corpo pelo schema.
 * Toda divergência é erro (nível default {@code ERROR}); nenhuma vira só aviso.
 */
final class ContratoOpenApi {

    /** Caminho estável do contrato: o repositório do mainframe o baixa por URL raw da {@code main}. */
    static final String CAMINHO = "contrato/openapi.yaml";

    private static final OpenApiInteractionValidator VALIDADOR = validador(Path.of(CAMINHO));

    private ContratoOpenApi() {
    }

    /** Validador do contrato no {@code arquivo}, com toda divergência como erro. */
    static OpenApiInteractionValidator validador(Path arquivo) {
        return OpenApiInteractionValidator.createForSpecificationUrl(arquivo.toUri().toString())
                .withLevelResolver(LevelResolver.create().withDefaultLevel(ValidationReport.Level.ERROR).build())
                .build();
    }

    /** Falha o teste se a resposta real divergir do contrato, listando as divergências. */
    static void assertConforme(ExchangeResult resultado) {
        var relatorio = validar(VALIDADOR, resultado.getUrl().getRawPath(), resultado.getStatus().value(),
                resultado.getResponseHeaders(), resultado.getResponseBodyContent());
        assertThat(relatorio.hasErrors())
                .as("Resposta de %s %s (%s) diverge de %s:%n%s", resultado.getMethod(), resultado.getUrl(),
                        resultado.getStatus(), CAMINHO, SimpleValidationReportFormat.getInstance().apply(relatorio))
                .isFalse();
    }

    /** Divergências de uma resposta a {@code GET caminho} em relação ao contrato do {@code validador}. */
    static ValidationReport validar(OpenApiInteractionValidator validador, String caminho, int status,
                                    HttpHeaders headers, byte[] corpo) {
        var resposta = SimpleResponse.Builder.status(status);
        headers.forEach(resposta::withHeader);
        var contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            resposta.withContentType(contentType);
        }
        if (corpo != null && corpo.length > 0) {
            resposta.withBody(corpo);
        }
        return validador.validateResponse(caminho, Request.Method.GET, resposta.build());
    }
}
