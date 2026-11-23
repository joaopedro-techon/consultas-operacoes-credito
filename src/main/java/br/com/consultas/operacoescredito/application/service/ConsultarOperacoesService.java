package br.com.consultas.operacoescredito.application.service;

import br.com.consultas.operacoescredito.application.port.in.Consulta;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException;

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
