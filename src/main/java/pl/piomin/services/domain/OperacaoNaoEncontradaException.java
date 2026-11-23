package pl.piomin.services.domain;

/** A operação de crédito consultada por {@code id_operacao} não existe na fonte. */
public class OperacaoNaoEncontradaException extends RuntimeException {

    private final long idOperacao;

    public OperacaoNaoEncontradaException(long idOperacao) {
        super("Operação de crédito " + idOperacao + " não encontrada");
        this.idOperacao = idOperacao;
    }

    public long idOperacao() {
        return idOperacao;
    }
}
