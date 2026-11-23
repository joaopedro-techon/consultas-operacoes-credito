package pl.piomin.services.application.service;

import pl.piomin.services.application.port.in.Consulta;
import pl.piomin.services.application.port.in.ConsultarOperacoesUseCase;
import pl.piomin.services.application.port.out.FonteDeOperacoes;
import pl.piomin.services.domain.CriterioConsulta;
import pl.piomin.services.domain.OperacaoConsultada;
import pl.piomin.services.domain.OperacaoNaoEncontradaException;

import java.util.List;
import java.util.Objects;

/** Implementação do caso de uso de consulta; sem dependência de framework (ligada em {@code config}). */
public class ConsultarOperacoesService implements ConsultarOperacoesUseCase {

    private final FonteDeOperacoes fonte;

    public ConsultarOperacoesService(FonteDeOperacoes fonte) {
        this.fonte = Objects.requireNonNull(fonte, "fonte");
    }

    @Override
    public List<OperacaoConsultada> consultar(Consulta consulta) {
        var operacoes = fonte.buscar(consulta.criterio(), consulta.blocos());
        if (operacoes.isEmpty() && consulta.criterio() instanceof CriterioConsulta.PorOperacao porOperacao) {
            throw new OperacaoNaoEncontradaException(porOperacao.idOperacao());
        }
        return operacoes;
    }
}
