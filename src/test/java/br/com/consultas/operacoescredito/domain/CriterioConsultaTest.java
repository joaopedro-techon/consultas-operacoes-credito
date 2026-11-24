package br.com.consultas.operacoescredito.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CriterioConsultaTest {

    @Test
    void contaDeAteVinteDigitosEAceita() {
        var conta = "9".repeat(CriterioConsulta.PorConta.TAMANHO_MAXIMO_CONTA);

        assertThat(new CriterioConsulta.PorConta(conta, OptionalInt.empty()).conta()).isEqualTo(conta);
        assertThat(new CriterioConsulta.PorConta("1", OptionalInt.of(500)).tipo()).isEqualTo(TipoConsulta.CONTA);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"123456789012345678901", "12a4", "-1", "434115 000065874"})
    void contaForaDoFormatoOuLongaDemaisERejeitada(String conta) {
        assertThatThrownBy(() -> new CriterioConsulta.PorConta(conta, OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contaComTresMilDigitosERejeitadaAntesDeChegarAFonte() {
        assertThatThrownBy(() -> new CriterioConsulta.PorConta("1".repeat(3000), OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501, -1})
    void limiteForaDe1a500ERejeitado(int limite) {
        assertThatThrownBy(() -> new CriterioConsulta.PorCliente("52998224725", OptionalInt.of(limite)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clienteComDocumentoInvalidoEOperacaoNaoPositivaSaoRejeitados() {
        assertThatThrownBy(() -> new CriterioConsulta.PorCliente("52998224724", OptionalInt.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CriterioConsulta.PorOperacao(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
