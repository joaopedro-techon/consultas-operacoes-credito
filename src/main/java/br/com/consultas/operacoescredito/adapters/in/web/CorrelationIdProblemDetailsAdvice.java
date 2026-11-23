package br.com.consultas.operacoescredito.adapters.in.web;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Inclui o {@code correlation_id} da requisição em todo Problem Details, venha ele de um
 * {@code @ExceptionHandler} próprio ou dos handlers padrão do Spring MVC.
 */
@RestControllerAdvice
class CorrelationIdProblemDetailsAdvice implements ResponseBodyAdvice<Object> {

    static final String PROPRIEDADE_CORRELATION_ID = "correlation_id";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        var correlationId = MDC.get(ContextoRequisicaoFilter.MDC_CORRELATION_ID);
        if (body instanceof ProblemDetail problema && correlationId != null) {
            problema.setProperty(PROPRIEDADE_CORRELATION_ID, correlationId);
        }
        return body;
    }
}
