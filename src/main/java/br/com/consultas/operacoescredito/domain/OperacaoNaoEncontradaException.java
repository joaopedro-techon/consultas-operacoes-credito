package br.com.consultas.operacoescredito.domain;

/** A operação de crédito consultada por {@code id_operacao} não existe na fonte. */
public class OperacaoNaoEncontradaException extends RuntimeException {

    public OperacaoNaoEncontradaException(long idOperacao) {
        super("Operação de crédito " + idOperacao + " não encontrada");
    }
}
