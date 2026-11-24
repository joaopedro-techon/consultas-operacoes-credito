package br.com.consultas.operacoescredito.domain.selecao;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CatalogoDeTeste;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelecaoDeCamposTest {

    private static final CatalogoDeCampos CATALOGO = CatalogoDeTeste.catalogo();
    private static final Set<Bloco> PEDIDOS = EnumSet.of(Bloco.OPERACAO, Bloco.ORIGINACAO, Bloco.PARCELAS, Bloco.SALDO);

    private final OperacaoConsultada operacao = operacao();

    @Test
    void campoSelecionadoVoltaComOIdOperacaoEBlocoSemSelecaoVoltaCompleto() {
        var selecao = SelecaoDeCampos.de(List.of("operacao.situacao_operacao_credito"), PEDIDOS, CATALOGO);

        var resultado = selecao.aplicar(operacao).blocos();

        assertThat(resultado.get(Bloco.OPERACAO))
                .isEqualTo(Map.of("id_operacao", 1L, "situacao_operacao_credito", "ATIVA"));
        assertThat(resultado.get(Bloco.ORIGINACAO)).isEqualTo(operacao.blocos().get(Bloco.ORIGINACAO));
    }

    @Test
    void selecaoSoDoIdOperacaoReduzOBlocoAEle() {
        var selecao = SelecaoDeCampos.de(List.of("operacao.id_operacao"), PEDIDOS, CATALOGO);

        assertThat(selecao.aplicar(operacao).blocos().get(Bloco.OPERACAO)).isEqualTo(Map.of("id_operacao", 1L));
    }

    @Test
    void subcampoDeListaAninhadaProjetaCadaItemECompostoInteiroVoltaCompleto() {
        var sub = SelecaoDeCampos.de(List.of("saldo.saldo_parcelas.saldo_devedor"), PEDIDOS, CATALOGO);
        var inteiro = SelecaoDeCampos.de(List.of("saldo.saldo_parcelas"), PEDIDOS, CATALOGO);

        assertThat(sub.aplicar(operacao).blocos().get(Bloco.SALDO)).isEqualTo(Map.of("id_operacao", 1L,
                "saldo_parcelas", List.of(Map.of("saldo_devedor", "5.00"))));
        assertThat(inteiro.aplicar(operacao).blocos().get(Bloco.SALDO)).isEqualTo(Map.of("id_operacao", 1L,
                "saldo_parcelas", List.of(Map.of("saldo_devedor", "5.00", "numero", 1))));
    }

    @Test
    void blocoIndisponivelContinuaNulo() {
        var blocos = new HashMap<>(operacao.blocos());
        blocos.put(Bloco.PARCELAS, null);
        var selecao = SelecaoDeCampos.de(List.of("parcelas.situacao_parcela"), PEDIDOS, CATALOGO);

        assertThat(selecao.aplicar(new OperacaoConsultada(blocos)).blocos()).containsEntry(Bloco.PARCELAS, null);
    }

    @Test
    void semCamposNaoMudaNada() {
        assertThat(SelecaoDeCampos.de(List.of(), PEDIDOS, CATALOGO).aplicar(operacao)).isSameAs(operacao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"operacao", "operacao.", ".campo", "xpto.campo", "operacao.xpto",
            "parcelas.situacao_parcela.mais"})
    void itemMalformadoOuForaDoCatalogoERejeitado(String item) {
        assertThatThrownBy(() -> SelecaoDeCampos.de(List.of(item), PEDIDOS, CATALOGO))
                .isInstanceOf(SelecaoDeCamposInvalidaException.class)
                .hasMessageContaining("fields");
    }

    @Test
    void campoDeBlocoForaDoExpandERejeitado() {
        assertThatThrownBy(() -> SelecaoDeCampos.de(List.of("parcelas.situacao_parcela"), Bloco.semprePresentes(),
                CATALOGO))
                .isInstanceOf(SelecaoDeCamposInvalidaException.class)
                .hasMessageContaining("expand");
    }

    private static OperacaoConsultada operacao() {
        var blocos = new HashMap<Bloco, Object>();
        blocos.put(Bloco.OPERACAO, Map.of("id_operacao", 1L, "situacao_operacao_credito", "ATIVA",
                "data_contratacao_operacao", "2026-01-10"));
        blocos.put(Bloco.ORIGINACAO, Map.of("id_operacao", 1L, "dependencia_contabil", "0001"));
        blocos.put(Bloco.PARCELAS, List.of(Map.of("id_operacao", 1L, "situacao_parcela", "ABERTA")));
        blocos.put(Bloco.SALDO, Map.of("id_operacao", 1L, "saldo_parcelas",
                List.of(Map.of("saldo_devedor", "5.00", "numero", 1))));
        return new OperacaoConsultada(blocos);
    }
}
