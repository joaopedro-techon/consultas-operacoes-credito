package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Leva o MDC (correlation_id, consumidor, tipo_consulta, rota) às threads filhas via Micrometer
 * Context Propagation: quem envolver uma tarefa com {@code ContextSnapshotFactory.captureAll().wrap(...)}
 * ou um executor com {@code ContextExecutorService.wrap(...)} recebe o MDC da requisição, que é
 * restaurado ao fim da tarefa. Registrado via {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
 */
public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    public static final String CHAVE = "consultas.mdc";

    @Override
    public Object key() {
        return CHAVE;
    }

    @Override
    public Map<String, String> getValue() {
        var contexto = MDC.getCopyOfContextMap();
        return contexto == null || contexto.isEmpty() ? null : contexto;
    }

    @Override
    public void setValue(Map<String, String> value) {
        MDC.setContextMap(value);
    }

    @Override
    public void setValue() {
        MDC.clear();
    }
}
