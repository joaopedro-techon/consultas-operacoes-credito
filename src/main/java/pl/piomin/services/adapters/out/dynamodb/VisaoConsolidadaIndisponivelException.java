package pl.piomin.services.adapters.out.dynamodb;

/** A visão consolidada não conseguiu atender a leitura. */
class VisaoConsolidadaIndisponivelException extends RuntimeException {

    VisaoConsolidadaIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
