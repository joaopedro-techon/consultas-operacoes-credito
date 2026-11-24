package br.com.consultas.operacoescredito.domain.catalogo;

/** O catálogo de campos configurado é inconsistente; a aplicação não deve subir com ele. */
public class CatalogoInvalidoException extends RuntimeException {

    public CatalogoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
