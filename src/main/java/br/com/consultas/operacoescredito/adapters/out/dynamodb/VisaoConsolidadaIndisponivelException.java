package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;

/** A visão consolidada não conseguiu atender a leitura (ex.: chaves não processadas depois das releituras): 502. */
class VisaoConsolidadaIndisponivelException extends FalhaNaFonteDeOperacoesException {

    VisaoConsolidadaIndisponivelException(String mensagem) {
        super(mensagem);
    }

    VisaoConsolidadaIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
