package br.com.consultas.operacoescredito.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Conjunto de dados que a consulta pode trazer de uma operação de crédito.
 * O nome canônico é o mesmo no {@code expand} e na chave do JSON de resposta.
 */
public enum Bloco {

    OPERACAO("operacao"),
    ORIGINACAO("originacao"),
    COMPONENTES("componentes"),
    PARCELAS("parcelas"),
    AMORTIZACOES("amortizacoes"),
    RECEBIMENTOS("recebimentos"),
    PLANO("plano"),
    SALDO("saldo"),
    CONTROLES_IOF("controles_iof");

    private final String nome;

    Bloco(String nome) {
        this.nome = nome;
    }

    public String nome() {
        return nome;
    }

    /** Bloco cujo nome canônico é {@code nome} (sensível a maiúsculas). */
    public static Optional<Bloco> porNome(String nome) {
        return Arrays.stream(values()).filter(bloco -> bloco.nome.equals(nome)).findFirst();
    }

    /** Blocos que vêm em toda consulta, com ou sem {@code expand}. */
    public static Set<Bloco> semprePresentes() {
        return EnumSet.of(OPERACAO, ORIGINACAO);
    }
}
