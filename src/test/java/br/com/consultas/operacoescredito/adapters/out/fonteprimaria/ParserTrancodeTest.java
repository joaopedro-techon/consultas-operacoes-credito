package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cabeçalho de paginação ({@code QQQ} + {@code S}/{@code N} + chave de 20, ADR-0016) e campos ignorados. */
class ParserTrancodeTest {

    private static final LayoutTrancode PARCELAS = new LayoutTrancode("parcelas", LayoutTrancode.Estrutura.LISTA,
            new LayoutTrancode.Registro(List.of(
                    new LayoutTrancode.Campo("numero_parcela", 1, 3, TipoCampoTrancode.NUMERO, null, null)),
                    List.of()));
    private static final LayoutTrancode COM_IGNORADO = new LayoutTrancode("operacao",
            LayoutTrancode.Estrutura.OBJETO, new LayoutTrancode.Registro(List.of(
                    new LayoutTrancode.Campo("id_operacao", 1, 3, TipoCampoTrancode.NUMERO, null, null),
                    new LayoutTrancode.Campo("codigo_produto_od", 4, 6, TipoCampoTrancode.TEXTO, null, true)),
                    List.of()));

    private final ParserTrancode parser = new ParserTrancode();

    @Test
    void paginaComContinuacaoDevolveOsItensEAChaveDaProxima() {
        var pagina = parser.lerPagina("002S" + chave("PARCELAS000031") + "001002", PARCELAS);

        assertThat(pagina.itens()).containsExactly(Map.of("numero_parcela", 1L), Map.of("numero_parcela", 2L));
        assertThat(pagina.proxima()).contains("PARCELAS000031");
    }

    @Test
    void ultimaPaginaNaoTemProxima() {
        var pagina = parser.lerPagina("001N" + chave("") + "075", PARCELAS);

        assertThat(pagina.itens()).hasSize(1);
        assertThat(pagina.proxima()).isEmpty();
    }

    @Test
    void paginaComMaisDe30OcorrenciasEInvalida() {
        assertThatThrownBy(() -> parser.lerPagina("031N" + chave("") + "001".repeat(31), PARCELAS))
                .isInstanceOf(TrancodeInvalidoException.class)
                .hasMessageContaining("máximo é 30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"X", "S"})
    void indicadorInvalidoOuContinuacaoSemChaveEInvalido(String indicador) {
        assertThatThrownBy(() -> parser.lerPagina("001" + indicador + chave("") + "001", PARCELAS))
                .isInstanceOf(TrancodeInvalidoException.class);
    }

    @Test
    void campoIgnoradoOcupaAsPosicoesMasNaoAparece() {
        assertThat(parser.lerObjeto("042CNSG01", COM_IGNORADO)).containsExactly(Map.entry("id_operacao", 42L));
    }

    @Test
    void layoutPaginadoNaoELidoComoObjeto() {
        assertThatThrownBy(() -> parser.lerObjeto("000N" + chave(""), PARCELAS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String chave(String chave) {
        return String.format("%-" + LayoutTrancode.TAMANHO_CHAVE_CONTINUACAO + "s", chave);
    }
}
