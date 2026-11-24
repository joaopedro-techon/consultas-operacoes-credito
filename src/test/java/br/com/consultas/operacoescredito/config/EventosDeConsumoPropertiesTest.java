package br.com.consultas.operacoescredito.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventosDeConsumoPropertiesTest {

    private static final String SEGREDO = "segredo-de-teste-com-pelo-menos-32-bytes";

    @Test
    void segredoComMenosDe32BytesImpedeASubidaComMensagemClara() {
        assertThatThrownBy(() -> new EventosDeConsumoProperties(true, "a".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consultas.eventos.segredo-hmac")
                .hasMessageContaining("pelo menos 32 bytes")
                .message().doesNotContain("a".repeat(31));
    }

    @Test
    void segredoDe32BytesOuMaisEAceito() {
        assertThat(new EventosDeConsumoProperties(true, "a".repeat(32)).segredoHmac()).hasSize(32);
        // 16 caracteres de 2 bytes em UTF-8 = 32 bytes
        assertThat(new EventosDeConsumoProperties(true, "ç".repeat(16)).habilitado()).isTrue();
    }

    @Test
    void segredoAusenteEObrigatorioSoComOEventoLigado() {
        assertThatThrownBy(() -> new EventosDeConsumoProperties(true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatório");
        assertThat(new EventosDeConsumoProperties(false, "curto").habilitado()).isFalse();
    }

    @Test
    void toStringNuncaMostraOSegredo() {
        var properties = new EventosDeConsumoProperties(true, SEGREDO);

        assertThat(properties.toString()).doesNotContain(SEGREDO).contains("habilitado=true").contains("******");
        assertThat(new EventosDeConsumoProperties(false, null).toString()).contains("<vazio>");
    }
}
