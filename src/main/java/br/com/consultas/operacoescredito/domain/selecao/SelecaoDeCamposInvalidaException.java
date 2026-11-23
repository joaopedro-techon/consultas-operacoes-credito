package br.com.consultas.operacoescredito.domain.selecao;

/** O parâmetro {@code fields} cita item malformado, bloco desconhecido ou fora do expand, ou campo fora do catálogo. */
public class SelecaoDeCamposInvalidaException extends RuntimeException {

    public SelecaoDeCamposInvalidaException(String mensagem) {
        super(mensagem);
    }
}
