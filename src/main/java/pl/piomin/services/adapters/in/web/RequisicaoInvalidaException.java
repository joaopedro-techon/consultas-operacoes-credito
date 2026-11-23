package pl.piomin.services.adapters.in.web;

/** Header ou parâmetro inválido ou incoerente com o tipo de consulta (400). */
class RequisicaoInvalidaException extends RuntimeException {

    RequisicaoInvalidaException(String mensagem) {
        super(mensagem);
    }
}
