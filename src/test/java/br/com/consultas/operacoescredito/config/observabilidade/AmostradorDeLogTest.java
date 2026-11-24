package br.com.consultas.operacoescredito.config.observabilidade;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class AmostradorDeLogTest {

    private final RelogioManual relogio = new RelogioManual();
    private final AmostradorDeLog amostrador = new AmostradorDeLog(Duration.ofSeconds(5), relogio);

    @Test
    void umLogPorChavePorIntervaloContandoOsSuprimidos() {
        assertThat(amostrador.permitir("cb:F5")).hasValue(0);
        assertThat(amostrador.permitir("cb:F5")).isEmpty();
        assertThat(amostrador.permitir("cb:F5")).isEmpty();
        assertThat(amostrador.permitir("cb:SF")).as("outra chave não é suprimida").hasValue(0);

        relogio.avancar(Duration.ofSeconds(5));

        assertThat(amostrador.permitir("cb:F5")).as("os dois suprimidos").isEqualTo(OptionalLong.of(2));
        assertThat(amostrador.permitir("cb:F5")).isEmpty();
    }

    private static final class RelogioManual extends Clock {

        private Instant agora = Instant.parse("2026-01-01T00:00:00Z");

        void avancar(Duration duracao) {
            agora = agora.plus(duracao);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
