package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Custodia;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Os blocos de uma operação em uma custódia no cache: um hash {@code op:{id_operacao}:{custodia}} (ADR-0018), com
 * o campo {@code versao} e um campo por bloco, pelo nome canônico.
 */
public record ChaveDeOperacao(long idOperacao, Custodia custodia) {

    public ChaveDeOperacao {
        Objects.requireNonNull(custodia, "custodia");
    }

    /** Chave no Valkey, ex.: {@code op:100000001:SF}. */
    public String valor() {
        return "op:" + idOperacao + ":" + custodia.codigo();
    }

    byte[] bytes() {
        return valor().getBytes(StandardCharsets.UTF_8);
    }
}
