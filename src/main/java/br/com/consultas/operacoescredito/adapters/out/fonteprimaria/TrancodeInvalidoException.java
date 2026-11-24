package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;

/** Trancode devolvido pela fonte primária não segue o layout do bloco. */
class TrancodeInvalidoException extends FalhaNaFonteDeOperacoesException {

    TrancodeInvalidoException(String mensagem) {
        super(mensagem);
    }

    TrancodeInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
