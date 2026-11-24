package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Bloco;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Um bloco de uma operação no cache: chave {@code op:{id_operacao}:{bloco}}, com o nome canônico do bloco.
 */
public record ChaveDeBloco(long idOperacao, Bloco bloco) {

    public ChaveDeBloco {
        Objects.requireNonNull(bloco, "bloco");
    }

    /** Chave no Valkey, ex.: {@code op:100000001:parcelas}. */
    public String valor() {
        return "op:" + idOperacao + ":" + bloco.nome();
    }

    byte[] bytes() {
        return valor().getBytes(StandardCharsets.UTF_8);
    }
}
