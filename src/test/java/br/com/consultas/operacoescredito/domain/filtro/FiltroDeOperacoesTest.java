package br.com.consultas.operacoescredito.domain.filtro;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CatalogoDeTeste;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltroDeOperacoesTest {

    private static final CatalogoDeCampos CATALOGO = CatalogoDeTeste.catalogo();
    private static final Set<Bloco> COM_PARCELAS_E_SALDO = EnumSet.of(Bloco.OPERACAO, Bloco.ORIGINACAO, Bloco.PARCELAS,
            Bloco.SALDO);

    private final OperacaoConsultada ativaNaAgencia1 = operacao(1, "ATIVA", "0001");
    private final OperacaoConsultada liquidadaNaAgencia2 = operacao(2, "LIQUIDADA", "0002");
    private final OperacaoConsultada liquidadaNaAgencia3 = operacao(3, "LIQUIDADA", "0003");

    @Test
    void ouEntreOperacaoEOriginacaoFiltraAOperacao() {
        var filtro = compilar(ou(
                comparacao("operacao.situacao_operacao_credito", OperadorComparacao.IGUAL, "ATIVA"),
                comparacao("originacao.dependencia_contabil", OperadorComparacao.IGUAL, "0002")));

        assertThat(filtro.aplicar(List.of(ativaNaAgencia1, liquidadaNaAgencia2, liquidadaNaAgencia3)))
                .containsExactly(ativaNaAgencia1, liquidadaNaAgencia2);
    }

    @Test
    void eEntreOperacaoEOriginacaoExigeAsDuas() {
        var filtro = compilar(new ExpressaoFiltro.E(List.of(
                comparacao("operacao.situacao_operacao_credito", OperadorComparacao.IGUAL, "LIQUIDADA"),
                comparacao("originacao.dependencia_contabil", OperadorComparacao.IGUAL, "0003"))));

        assertThat(filtro.aplicar(List.of(ativaNaAgencia1, liquidadaNaAgencia2, liquidadaNaAgencia3)))
                .containsExactly(liquidadaNaAgencia3);
    }

    @Test
    void operacaoSemOBlocoDaCondicaoNaoAtende() {
        var semOriginacao = new HashMap<Bloco, Object>(ativaNaAgencia1.blocos());
        semOriginacao.put(Bloco.ORIGINACAO, null);
        var filtro = compilar(comparacao("originacao.dependencia_contabil", OperadorComparacao.DIFERENTE, "9999"));

        assertThat(filtro.aplicar(List.of(new OperacaoConsultada(semOriginacao), liquidadaNaAgencia2)))
                .containsExactly(liquidadaNaAgencia2);
    }

    @Test
    void ouMisturandoAOperacaoEUmaListaERejeitado() {
        var expressao = ou(
                comparacao("operacao.situacao_operacao_credito", OperadorComparacao.IGUAL, "ATIVA"),
                comparacao("parcelas.situacao_parcela", OperadorComparacao.IGUAL, "ABERTA"));

        assertThatThrownBy(() -> compilar(expressao))
                .isInstanceOf(FiltroInvalidoException.class)
                .hasMessageContaining("operacao/originacao")
                .hasMessageContaining("parcelas");
    }

    @Test
    void ouMisturandoDuasListasERejeitado() {
        var expressao = ou(
                comparacao("parcelas.situacao_parcela", OperadorComparacao.IGUAL, "ABERTA"),
                comparacao("saldo.saldo_parcelas.saldo_devedor", OperadorComparacao.MAIOR, "0"));

        assertThatThrownBy(() -> compilar(expressao)).isInstanceOf(FiltroInvalidoException.class);
    }

    @Test
    void condicaoNumaListaMantemAOperacaoERemoveSoOsItens() {
        var filtro = compilar(comparacao("parcelas.numero_parcela_amortizacao", OperadorComparacao.MAIOR_OU_IGUAL, "2"));

        var resultado = filtro.aplicar(List.of(ativaNaAgencia1));

        assertThat(resultado).singleElement().satisfies(operacao -> assertThat(
                (List<?>) operacao.blocos().get(Bloco.PARCELAS)).hasSize(1));
    }

    @Test
    void campoVazioDepoisDoBlocoERejeitado() {
        assertThatThrownBy(() -> compilar(comparacao("parcelas.", OperadorComparacao.IGUAL, "1")))
                .isInstanceOf(FiltroInvalidoException.class)
                .hasMessageContaining("não existe no catálogo");
    }

    @Test
    void campoDesconhecidoERejeitado() {
        assertThatThrownBy(() -> compilar(comparacao("parcelas.xpto", OperadorComparacao.IGUAL, "1")))
                .isInstanceOf(FiltroInvalidoException.class)
                .hasMessageContaining("parcelas.xpto");
    }

    @Test
    void seletorSemBlocoOuComBlocoDesconhecidoOuForaDoExpandERejeitado() {
        assertThatThrownBy(() -> compilar(comparacao("situacao", OperadorComparacao.IGUAL, "A")))
                .isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("<bloco>.<campo>");
        assertThatThrownBy(() -> compilar(comparacao("xpto.campo", OperadorComparacao.IGUAL, "A")))
                .isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("Blocos válidos");
        var semParcelas = FiltroDeOperacoes.compilar(comparacao("operacao.situacao_operacao_credito",
                OperadorComparacao.IGUAL, "A"), CATALOGO, Bloco.semprePresentes());
        assertThat(semParcelas).isNotNull();
        assertThatThrownBy(() -> FiltroDeOperacoes.compilar(comparacao("parcelas.situacao_parcela",
                OperadorComparacao.IGUAL, "A"), CATALOGO, Bloco.semprePresentes()))
                .isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("expand");
    }

    @Test
    void operadorDeOrdemEmTextoEArgumentoForaDoTipoSaoRejeitados() {
        assertThatThrownBy(() -> compilar(comparacao("parcelas.situacao_parcela", OperadorComparacao.MAIOR, "A")))
                .isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("só aceita");
        assertThatThrownBy(() -> compilar(comparacao("parcelas.numero_parcela_amortizacao", OperadorComparacao.IGUAL,
                "quatro"))).isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("numero");
    }

    @Test
    void campoDePrimeiroNivelDeBlocoObjetoQueNaoEDaOperacaoERejeitado() {
        assertThatThrownBy(() -> compilar(comparacao("saldo.valor_total", OperadorComparacao.MAIOR, "0")))
                .isInstanceOf(FiltroInvalidoException.class).hasMessageContaining("não pode ser filtrado");
    }

    @Test
    void semFiltroMantemTudo() {
        var operacoes = List.of(ativaNaAgencia1, liquidadaNaAgencia2);

        assertThat(FiltroDeOperacoes.NENHUM.aplicar(operacoes)).isSameAs(operacoes);
    }

    private static FiltroDeOperacoes compilar(ExpressaoFiltro expressao) {
        return FiltroDeOperacoes.compilar(expressao, CATALOGO, COM_PARCELAS_E_SALDO);
    }

    private static ExpressaoFiltro ou(ExpressaoFiltro... operandos) {
        return new ExpressaoFiltro.Ou(List.of(operandos));
    }

    private static ExpressaoFiltro comparacao(String seletor, OperadorComparacao operador, String argumento) {
        return new ExpressaoFiltro.Comparacao(seletor, operador, List.of(argumento));
    }

    private static OperacaoConsultada operacao(long id, String situacao, String dependencia) {
        var blocos = new HashMap<Bloco, Object>();
        blocos.put(Bloco.OPERACAO, Map.of("id_operacao", id, "situacao_operacao_credito", situacao));
        blocos.put(Bloco.ORIGINACAO, Map.of("id_operacao", id, "dependencia_contabil", dependencia));
        blocos.put(Bloco.PARCELAS, List.of(
                Map.of("id_operacao", id, "numero_parcela_amortizacao", 1, "situacao_parcela", "PAGA"),
                Map.of("id_operacao", id, "numero_parcela_amortizacao", 2, "situacao_parcela", "ABERTA")));
        blocos.put(Bloco.SALDO, Map.of("id_operacao", id, "valor_total", "10.00"));
        return new OperacaoConsultada(blocos);
    }
}
