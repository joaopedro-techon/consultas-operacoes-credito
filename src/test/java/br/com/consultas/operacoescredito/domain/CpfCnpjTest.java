package br.com.consultas.operacoescredito.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CpfCnpjTest {

    @ParameterizedTest
    @ValueSource(strings = {"52998224725", "11144477735", "11222333000181", "12ABC34501DE35"})
    void cpfECnpjNumericoOuAlfanumericoComDvCorretoSaoValidos(String documento) {
        assertThat(CpfCnpj.valido(documento)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"52998224724", "52998224715", "11222333000180", "12ABC34501DE36", "12ABC34501DE3A"})
    void dvErradoNaoEValido(String documento) {
        assertThat(CpfCnpj.valido(documento)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000000", "11111111111", "99999999999", "00000000000000", "77777777777777"})
    void todosOsDigitosIguaisNaoEValidoMesmoPassandoNoModulo11(String documento) {
        assertThat(CpfCnpj.valido(documento)).isFalse();
    }

    @Test
    void cnpjAlfanumericoEmMinusculasNaoEValido() {
        assertThat(CpfCnpj.valido("12abc34501de35")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"529.982.247-25", "11.222.333/0001-81", "5299822472", "529982247250", "123456789012345",
            "1234567890123", "12ABC34501DE3", " 52998224725", "ABCDEFGHIJK"})
    void formatoForaDoContratoNaoEValido(String documento) {
        assertThat(CpfCnpj.valido(documento)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nuloOuVazioNaoEValido(String documento) {
        assertThat(CpfCnpj.valido(documento)).isFalse();
    }
}
