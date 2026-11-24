package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.filtro.ExpressaoFiltro;
import br.com.consultas.operacoescredito.domain.filtro.OperadorComparacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltroRsqlTest {

    @Test
    void eOuEComparacoesViramAExpressaoDoDominio() {
        var expressao = FiltroRsql.traduzir(
                "operacao.situacao_operacao_credito==ATIVA,originacao.dependencia_contabil=in=(1,2);parcelas.n=ge=4");

        assertThat(expressao).contains(new ExpressaoFiltro.Ou(List.of(
                new ExpressaoFiltro.Comparacao("operacao.situacao_operacao_credito", OperadorComparacao.IGUAL,
                        List.of("ATIVA")),
                new ExpressaoFiltro.E(List.of(
                        new ExpressaoFiltro.Comparacao("originacao.dependencia_contabil", OperadorComparacao.EM,
                                List.of("1", "2")),
                        new ExpressaoFiltro.Comparacao("parcelas.n", OperadorComparacao.MAIOR_OU_IGUAL,
                                List.of("4")))))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void filtroAusenteOuVazioNaoFiltra(String filter) {
        assertThat(FiltroRsql.traduzir(filter)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"parcelas.n", "parcelas.n==", "==1", "parcelas.n=xx=1", "(parcelas.n==1"})
    void sintaxeInvalidaE400ComExemplo(String filter) {
        assertThatThrownBy(() -> FiltroRsql.traduzir(filter))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("sintaxe RSQL inválida");
    }
}
