package br.com.consultas.operacoescredito.config.resiliencia;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Leva o {@link PrazoDaRequisicao} às threads filhas (fan-out) via Micrometer Context Propagation, como o
 * MDC. Registrado via {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
 */
public class PrazoDaRequisicaoThreadLocalAccessor implements ThreadLocalAccessor<Long> {

    public static final String CHAVE = "consultas.prazo";

    @Override
    public Object key() {
        return CHAVE;
    }

    @Override
    public Long getValue() {
        return PrazoDaRequisicao.limiteNanos();
    }

    @Override
    public void setValue(Long value) {
        PrazoDaRequisicao.definirLimiteNanos(value);
    }

    @Override
    public void setValue() {
        PrazoDaRequisicao.encerrar();
    }
}
