package br.com.consultas.operacoescredito.application.port.in;

import br.com.consultas.operacoescredito.domain.OperacaoConsultada;

import java.util.List;

/** Caso de uso único de consulta de operações de crédito, para qualquer tipo de consulta e fonte. */
public interface ConsultarOperacoesUseCase {

    /**
     * @throws br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException na consulta por operação
     *         quando a operação não existe
     */
    List<OperacaoConsultada> consultar(Consulta consulta);
}
