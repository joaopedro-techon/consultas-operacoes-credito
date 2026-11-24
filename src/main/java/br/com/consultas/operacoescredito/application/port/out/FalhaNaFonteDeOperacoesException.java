package br.com.consultas.operacoescredito.application.port.out;

/**
 * A fonte de operações não respondeu de forma utilizável (erro, resposta inválida ou fora do ar).
 * A requisição inteira falha; não há resposta parcial nem fallback para a outra fonte.
 */
public class FalhaNaFonteDeOperacoesException extends RuntimeException {

    public FalhaNaFonteDeOperacoesException(String mensagem) {
        super(mensagem);
    }

    public FalhaNaFonteDeOperacoesException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
