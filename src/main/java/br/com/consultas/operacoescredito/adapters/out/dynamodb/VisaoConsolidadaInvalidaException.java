package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;

/**
 * Item da visão consolidada fora do formato esperado (atributo ausente ou de outro tipo, SK desconhecida,
 * {@code texto_detalhe} que não é JSON em GZIP): dado inválido na fonte, 502. É lançada fora das chamadas ao SDK,
 * então não conta no circuit breaker (o DynamoDB respondeu).
 */
class VisaoConsolidadaInvalidaException extends FalhaNaFonteDeOperacoesException {

    VisaoConsolidadaInvalidaException(String mensagem) {
        super(mensagem);
    }

    VisaoConsolidadaInvalidaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
