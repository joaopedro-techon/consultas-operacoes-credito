package br.com.consultas.operacoescredito.adapters.out.eventos;

import br.com.consultas.operacoescredito.config.EventosDeConsumoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventosSqsPropertiesTest {

    private static final EventosSqsProperties.Fila FILA =
            new EventosSqsProperties.Fila(10_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(10));
    private static final EventosSqsProperties.Sqs SQS = sqs("http://localhost:4566/000000000000/fila");

    @Test
    void ligadoComFilaConfiguradaEValido() {
        assertThat(new EventosSqsProperties(true, FILA, SQS).sqs().urlFila()).endsWith("/fila");
    }

    @Test
    void ligadoSemUrlDaFilaFalhaNaSubida() {
        assertThatThrownBy(() -> new EventosSqsProperties(true, FILA, sqs(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url-fila");
    }

    @Test
    void desligadoNaoExigeUrlDaFila() {
        assertThat(new EventosSqsProperties(false, FILA, sqs(null)).habilitado()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void loteForaDeUmADezEInvalido(int tamanho) {
        assertThatThrownBy(() -> new EventosSqsProperties.Fila(10, tamanho, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tamanho-lote");
    }

    @Test
    void capacidadeEIntervaloPrecisamSerPositivos() {
        assertThatThrownBy(() -> new EventosSqsProperties.Fila(0, 10, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventosSqsProperties.Fila(10, 10, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segredoDoHmacEObrigatorioComOEventoLigado() {
        assertThatThrownBy(() -> new EventosDeConsumoProperties(true, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segredo-hmac");
        assertThat(new EventosDeConsumoProperties(false, null).habilitado()).isFalse();
    }

    private static EventosSqsProperties.Sqs sqs(String urlFila) {
        return new EventosSqsProperties.Sqs(urlFila, null, "sa-east-1", null, Duration.ofSeconds(1),
                Duration.ofSeconds(3));
    }
}
