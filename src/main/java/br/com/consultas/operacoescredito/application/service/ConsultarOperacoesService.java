package br.com.consultas.operacoescredito.application.service;

import br.com.consultas.operacoescredito.application.port.in.Consulta;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.OperacaoNaoEncontradaException;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.filtro.FiltroDeOperacoes;

import java.util.List;
import java.util.Objects;

/** Implementação do caso de uso de consulta; sem dependência de framework (ligada em {@code config}). */
public class ConsultarOperacoesService implements ConsultarOperacoesUseCase {

    private final FonteDeOperacoes fonte;
    private final CatalogoDeCampos catalogo;

    public ConsultarOperacoesService(FonteDeOperacoes fonte, CatalogoDeCampos catalogo) {
        this.fonte = Objects.requireNonNull(fonte, "fonte");
        this.catalogo = Objects.requireNonNull(catalogo, "catalogo");
    }

    @Override
    public List<OperacaoConsultada> consultar(Consulta consulta) {
        // Validado antes da leitura: filtro inválido é 400 sem custo na fonte.
        var filtro = consulta.filtro()
                .map(expressao -> FiltroDeOperacoes.compilar(expressao, catalogo, consulta.blocos()))
                .orElse(FiltroDeOperacoes.NENHUM);
        var operacoes = fonte.buscar(consulta.criterio(), consulta.blocos());
        if (operacoes.isEmpty() && consulta.criterio() instanceof CriterioConsulta.PorOperacao porOperacao) {
            throw new OperacaoNaoEncontradaException(porOperacao.idOperacao());
        }
        return filtro.aplicar(operacoes);
    }
}
