package br.com.consultas.operacoescredito.application.port.out;

/**
 * A fonte de operações não respondeu dentro do prazo (conexão, pool ou resposta). Como qualquer falha da
 * fonte, a requisição inteira falha, sem resposta parcial.
 */
public class TempoEsgotadoNaFonteDeOperacoesException extends FalhaNaFonteDeOperacoesException {

    public TempoEsgotadoNaFonteDeOperacoesException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
