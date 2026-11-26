package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Custodia;

import java.util.Objects;

/**
 * Os blocos de uma operação em uma custódia no cache: um hash cuja chave e campos dependem do
 * {@link FormatoDoHash formato} da regra de versão dos blocos (ADR-0018, ADR-0019).
 */
public record ChaveDeOperacao(long idOperacao, Custodia custodia) {

    public ChaveDeOperacao {
        Objects.requireNonNull(custodia, "custodia");
    }

    /** Chave do hash de versão única no Valkey, ex.: {@code op:100000001:SF}. */
    public String valor() {
        return FormatoDoHash.VersaoUnica.PREFIXO + idOperacao + ":" + custodia.codigo();
    }
}
