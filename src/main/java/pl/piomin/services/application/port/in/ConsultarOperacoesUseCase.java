package pl.piomin.services.application.port.in;

import pl.piomin.services.domain.OperacaoConsultada;

import java.util.List;

/** Caso de uso único de consulta de operações de crédito, para qualquer tipo de consulta e fonte. */
public interface ConsultarOperacoesUseCase {

    /**
     * @throws pl.piomin.services.domain.OperacaoNaoEncontradaException na consulta por operação
     *         quando a operação não existe
     */
    List<OperacaoConsultada> consultar(Consulta consulta);
}
