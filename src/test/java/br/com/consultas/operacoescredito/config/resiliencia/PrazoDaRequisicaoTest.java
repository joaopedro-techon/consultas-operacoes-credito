package br.com.consultas.operacoescredito.config.resiliencia;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrazoDaRequisicaoTest {

    @AfterEach
    void encerrar() {
        PrazoDaRequisicao.encerrar();
    }

    @Test
    void semPrazoNadaELimitado() {
        assertThat(PrazoDaRequisicao.restante()).isEmpty();
        assertThat(PrazoDaRequisicao.esgotado()).isFalse();
        assertThat(PrazoDaRequisicao.comporta(Duration.ofHours(1))).isTrue();
        assertThat(PrazoDaRequisicao.limitar(Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void comPrazoLimitaEsperasAoQueResta() {
        PrazoDaRequisicao.iniciar(Duration.ofMillis(500));

        assertThat(PrazoDaRequisicao.restante()).hasValueSatisfying(
                restante -> assertThat(restante).isPositive().isLessThanOrEqualTo(Duration.ofMillis(500)));
        assertThat(PrazoDaRequisicao.limitar(Duration.ofSeconds(1))).isLessThanOrEqualTo(Duration.ofMillis(500));
        assertThat(PrazoDaRequisicao.limitar(Duration.ofMillis(10))).isEqualTo(Duration.ofMillis(10));
        assertThat(PrazoDaRequisicao.comporta(Duration.ofMillis(10))).isTrue();
        assertThat(PrazoDaRequisicao.comporta(Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void prazoEsgotadoNaoComportaNadaELimitaA1Ms() throws InterruptedException {
        PrazoDaRequisicao.iniciar(Duration.ofMillis(1));
        Thread.sleep(5);

        assertThat(PrazoDaRequisicao.esgotado()).isTrue();
        assertThat(PrazoDaRequisicao.restante()).hasValue(Duration.ZERO);
        assertThat(PrazoDaRequisicao.comporta(Duration.ofMillis(1))).isFalse();
        assertThat(PrazoDaRequisicao.limitar(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void prazoChegaAsThreadsFilhasPelaPropagacaoDeContexto() throws Exception {
        PrazoDaRequisicao.iniciar(Duration.ofSeconds(30));
        var contexto = ContextSnapshotFactory.builder().build();

        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var restanteNaFilha = executor.submit(PrazoDaRequisicao::restante).get();
            assertThat(restanteNaFilha).hasValueSatisfying(restante -> assertThat(restante).isPositive());
        }
    }

    @Test
    void prazoPorTipoDeConsultaTemOsDefaultsDoRequisitoEExigeValorPositivo() {
        var prazos = new PrazoProperties(Duration.ofMillis(150), Duration.ofMillis(400), Duration.ofMillis(400));

        assertThat(prazos.de(TipoConsulta.OPERACAO)).isEqualTo(Duration.ofMillis(150));
        assertThat(prazos.de(TipoConsulta.CLIENTE)).isEqualTo(Duration.ofMillis(400));
        assertThat(prazos.de(TipoConsulta.CONTA)).isEqualTo(Duration.ofMillis(400));
        assertThatThrownBy(() -> new PrazoProperties(Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
