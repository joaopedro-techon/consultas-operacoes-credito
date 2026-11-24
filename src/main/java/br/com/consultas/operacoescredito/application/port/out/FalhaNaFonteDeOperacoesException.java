package br.com.consultas.operacoescredito.application.port.out;

/**
 * A fonte de operações não respondeu de forma utilizável (erro, resposta inválida ou fora do ar).
 * A requisição inteira falha, sem fallback para a outra fonte. Na fonte primária, a falha de um bloco que não é
 * {@code operacao} não chega aqui: o bloco fica indisponível e os demais seguem (ADR-0013).
 */
public class FalhaNaFonteDeOperacoesException extends RuntimeException {

    public FalhaNaFonteDeOperacoesException(String mensagem) {
        super(mensagem);
    }

    public FalhaNaFonteDeOperacoesException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
