package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Repassa o {@code x-correlationid} da requisição ao gateway do mainframe. O valor vem do MDC, onde o
 * filtro de contexto o coloca (chave {@code correlation_id}) e de onde a propagação de contexto o leva
 * às virtual threads das chamadas por bloco. O {@code traceparent} é injetado pela observação do
 * {@code RestClient}.
 */
final class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    static final String HEADER_CORRELATION_ID = "x-correlationid";
    /** Mesma chave usada pelo filtro de contexto da requisição (adapter de entrada). */
    static final String MDC_CORRELATION_ID = "correlation_id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        var correlationId = MDC.get(MDC_CORRELATION_ID);
        if (correlationId != null) {
            request.getHeaders().set(HEADER_CORRELATION_ID, correlationId);
        }
        return execution.execute(request, body);
    }
}
