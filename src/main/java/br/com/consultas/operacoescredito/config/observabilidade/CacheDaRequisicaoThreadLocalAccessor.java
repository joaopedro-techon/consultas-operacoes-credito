package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Leva a contagem de {@link CacheDaRequisicao} às threads filhas (fan-out) via Micrometer Context Propagation:
 * a thread filha registra na mesma contagem da requisição. Registrado via
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}.
 */
public class CacheDaRequisicaoThreadLocalAccessor implements ThreadLocalAccessor<CacheDaRequisicao.Contagem> {

    public static final String CHAVE = "consultas.cache-da-requisicao";

    @Override
    public Object key() {
        return CHAVE;
    }

    @Override
    public CacheDaRequisicao.Contagem getValue() {
        return CacheDaRequisicao.atual();
    }

    @Override
    public void setValue(CacheDaRequisicao.Contagem value) {
        CacheDaRequisicao.definir(value);
    }

    @Override
    public void setValue() {
        CacheDaRequisicao.encerrar();
    }
}
