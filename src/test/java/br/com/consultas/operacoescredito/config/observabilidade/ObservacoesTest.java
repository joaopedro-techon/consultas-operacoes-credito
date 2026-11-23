package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ObservacoesTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();

    @Test
    void dependenciaTemNomeFixoETagsDeDependenciaEOperacao() {
        Observacoes.dependencia(registry, "valkey", "GET").observe(() -> { });

        assertThat(registry).hasObservationWithNameEqualTo("consultas.dependencia")
                .that()
                .hasContextualNameEqualTo("valkey GET")
                .hasLowCardinalityKeyValue("dependencia", "valkey")
                .hasLowCardinalityKeyValue("operacao", "GET")
                .hasBeenStopped();
    }

    @Test
    void etapaTemNomeFixoETagDeEtapa() {
        Observacoes.etapa(registry, "fan_out").observe(() -> { });

        assertThat(registry).hasObservationWithNameEqualTo("consultas.etapa")
                .that()
                .hasContextualNameEqualTo("fan_out")
                .hasLowCardinalityKeyValue("etapa", "fan_out");
    }

    @Test
    void erroDaEtapaFicaRegistradoNaObservacao() {
        var falha = new IllegalStateException("falhou");

        try {
            Observacoes.etapa(registry, "filtro").observe(() -> {
                throw falha;
            });
        } catch (IllegalStateException esperado) {
            // a observação relança o erro
        }

        assertThat(registry).hasObservationWithNameEqualTo("consultas.etapa")
                .that()
                .hasError(falha);
    }

    @Test
    void dependenciaSemNomeEhRejeitada() {
        assertThatNullPointerException().isThrownBy(() -> Observacoes.dependencia(registry, null, "GET"));
        assertThatNullPointerException().isThrownBy(() -> Observacoes.etapa(registry, null));
    }
}
