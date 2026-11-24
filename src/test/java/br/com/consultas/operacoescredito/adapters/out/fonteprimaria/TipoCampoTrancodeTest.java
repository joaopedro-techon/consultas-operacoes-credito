package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Datas do trancode: leitura estrita (sem ajuste para a data válida mais próxima) e data zerada como "sem data". */
class TipoCampoTrancodeTest {

    @ParameterizedTest(name = "{0} ({1}) -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            2024-02-29          | yyyy-MM-dd             | 2024-02-29
            20240131            | yyyyMMdd               | 2024-01-31
            2024-02-29T23:59:59 | yyyy-MM-dd'T'HH:mm:ss  | 2024-02-29T23:59:59
            """)
    void dataValidaViraIso(String valor, String formato, String esperado) {
        assertThat(campo(valor, formato).converter(valor)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource(delimiter = '|', textBlock = """
            2024-02-30          | yyyy-MM-dd
            2023-02-29          | yyyy-MM-dd
            2024-04-31          | yyyy-MM-dd
            2024-13-01          | yyyy-MM-dd
            20240230            | yyyyMMdd
            2024-02-30T10:00:00 | yyyy-MM-dd'T'HH:mm:ss
            2024-02-28T24:00:00 | yyyy-MM-dd'T'HH:mm:ss
            """)
    void dataInexistenteETrancodeInvalidoENaoEAjustada(String valor, String formato) {
        assertThatThrownBy(() -> campo(valor, formato).converter(valor))
                .isInstanceOf(TrancodeInvalidoException.class)
                .hasMessageContaining("data_teste");
    }

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource(delimiter = '|', textBlock = """
            0000-00-00          | yyyy-MM-dd
            00000000            | yyyyMMdd
            0000-00-00T00:00:00 | yyyy-MM-dd'T'HH:mm:ss
            """)
    void dataZeradaESemData(String valor, String formato) {
        assertThat(campo(valor, formato).converter(valor)).isNull();
    }

    @Test
    void mesZeradoNumaDataComAnoNaoESemDataEEInvalido() {
        assertThatThrownBy(() -> campo("2024-00-10", "yyyy-MM-dd").converter("2024-00-10"))
                .isInstanceOf(TrancodeInvalidoException.class);
    }

    @Test
    void textoNaoNumericoNumaDataEInvalido() {
        assertThatThrownBy(() -> campo("AAAA-BB-CC", "yyyy-MM-dd").converter("AAAA-BB-CC"))
                .isInstanceOf(TrancodeInvalidoException.class);
    }

    private static LayoutTrancode.Campo campo(String valor, String formato) {
        var tipo = formato.contains("HH") ? TipoCampoTrancode.DATA_HORA : TipoCampoTrancode.DATA;
        return new LayoutTrancode.Campo("data_teste", 1, valor.length(), tipo, formato, false);
    }
}
