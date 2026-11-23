package br.com.consultas.operacoescredito.adapters.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException;
import br.com.consultas.operacoescredito.domain.filtro.FiltroInvalidoException;

/** Todos os erros em Problem Details (RFC 9457). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OperacaoNaoEncontradaException.class)
    ProblemDetail operacaoNaoEncontrada(OperacaoNaoEncontradaException e) {
        return problema(HttpStatus.NOT_FOUND, "Operação de crédito não encontrada", e.getMessage());
    }

    @ExceptionHandler(RequisicaoInvalidaException.class)
    ProblemDetail requisicaoInvalida(RequisicaoInvalidaException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(FiltroInvalidoException.class)
    ProblemDetail filtroInvalido(FiltroInvalidoException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(TipoConsultaNaoImplementadoException.class)
    ProblemDetail tipoNaoImplementado(TipoConsultaNaoImplementadoException e) {
        return problema(HttpStatus.NOT_IMPLEMENTED, "Tipo de consulta não disponível", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail erroInesperado(Exception e) {
        LOG.error("Erro inesperado na consulta", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Erro inesperado ao processar a consulta");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        var problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }
}
