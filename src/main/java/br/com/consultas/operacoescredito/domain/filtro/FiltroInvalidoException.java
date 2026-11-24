package br.com.consultas.operacoescredito.domain.filtro;

/** Filtro que não pode ser aplicado: campo fora do catálogo, bloco fora do {@code expand}, argumento inválido. */
public class FiltroInvalidoException extends RuntimeException {

    public FiltroInvalidoException(String mensagem) {
        super(mensagem);
    }
}
