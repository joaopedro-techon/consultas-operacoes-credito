package br.com.consultas.operacoescredito.adapters.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.TipoConsultaNaoSuportadoException;
import br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException;
import br.com.consultas.operacoescredito.domain.selecao.SelecaoDeCamposInvalidaException;

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

    @ExceptionHandler(SelecaoDeCamposInvalidaException.class)
    ProblemDetail selecaoDeCamposInvalida(SelecaoDeCamposInvalidaException e) {
        return problema(HttpStatus.BAD_REQUEST, "Requisição inválida", e.getMessage());
    }

    @ExceptionHandler(TipoConsultaNaoSuportadoException.class)
    ProblemDetail tipoNaoSuportadoNaFonte(TipoConsultaNaoSuportadoException e) {
        return problema(HttpStatus.NOT_IMPLEMENTED, "Tipo de consulta não disponível", e.getMessage());
    }

    @ExceptionHandler(FalhaNaFonteDeOperacoesException.class)
    ProblemDetail falhaNaFonte(FalhaNaFonteDeOperacoesException e) {
        LOG.warn("Falha na fonte de operações: {}", e.getMessage(), e);
        return problema(HttpStatus.BAD_GATEWAY, "Fonte de dados indisponível",
                "A fonte de dados não respondeu de forma utilizável; tente novamente");
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
