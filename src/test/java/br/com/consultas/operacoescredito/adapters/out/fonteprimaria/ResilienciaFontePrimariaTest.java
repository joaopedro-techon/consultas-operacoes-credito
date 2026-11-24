package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienciaFontePrimariaTest {

    private final CircuitBreaker circuito = CircuitBreaker.of("teste", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(10)
            .waitDurationInOpenState(Duration.ofSeconds(7))
            .permittedNumberOfCallsInHalfOpenState(1)
            .recordException(FalhasDoGateway::falhaDaDependencia)
            .ignoreException(FalhasDoGateway::ignoradaPeloCircuito)
            .build());
    private final Retry retry = Retry.of("teste", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(1))
            .retryOnException(FalhasDoGateway::retentavel)
            .build());
    private final Bulkhead bulkhead = Bulkhead.of("fontePrimaria", BulkheadConfig.custom()
            .maxConcurrentCalls(2)
            .maxWaitDuration(Duration.ZERO)
            .build());
    private final Bulkhead bulkheadClienteConta = Bulkhead.of("fontePrimaria-cliente-conta", BulkheadConfig.custom()
            .maxConcurrentCalls(1)
            .maxWaitDuration(Duration.ZERO)
            .build());

    @Test
    void retentaFalha5xxAteDarCerto() {
        var tentativas = new AtomicInteger();
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());

        var resultado = resiliencia.executar(false, () -> {
            if (tentativas.incrementAndGet() < 3) {
                throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null);
            }
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(tentativas).hasValue(3);
    }

    @Test
    void naoRetentaErro4xx() {
        var tentativas = new AtomicInteger();
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());

        assertThatThrownBy(() -> resiliencia.executar(false, () -> {
            tentativas.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalida", null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(tentativas).hasValue(1);
    }

    @Test
    void circuitoAbertoViraIndisponivelComOTempoDeEsperaDoCircuito() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());
        circuito.transitionToOpenState();
        var tentativas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.executar(false, tentativas::incrementAndGet))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(7)));
        assertThat(tentativas).as("circuito aberto não chama nem retenta").hasValue(0);
    }

    @Test
    void rateLimiterSemPermissaoViraIndisponivelComOPeriodoDoLimiteENaoContaComoSucesso() {
        var resiliencia = resiliencia(RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(30))
                .timeoutDuration(Duration.ZERO)
                .build());
        resiliencia.executar(false, () -> "primeira");

        assertThatThrownBy(() -> resiliencia.executar(false, () -> "segunda"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(30)));
        assertThat(circuito.getMetrics().getNumberOfSuccessfulCalls()).as("só a primeira chamada").isEqualTo(1);
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void bulkheadCheioViraIndisponivelENaoContaNoCircuito() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());
        ocupar(bulkhead);

        assertThatThrownBy(() -> resiliencia.executar(false, () -> "sem vaga"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(1)));
        assertThat(circuito.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void bulkheadDeClienteContaCheioRecusaClienteContaMasNaoOperacao() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());
        ocupar(bulkheadClienteConta);

        assertThatThrownBy(() -> resiliencia.executar(true, () -> "cliente"))
                .isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                .hasMessageContaining("fontePrimaria-cliente-conta");
        assertThat(resiliencia.executar(false, () -> "operacao")).isEqualTo("operacao");
    }

    @Test
    void chamadasDeClienteContaOcupamOsDoisBulkheads() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());

        var vagas = resiliencia.executar(true, () -> new int[] {
                bulkheadClienteConta.getMetrics().getAvailableConcurrentCalls(),
                bulkhead.getMetrics().getAvailableConcurrentCalls()});

        assertThat(vagas).containsExactly(0, 1);
        assertThat(bulkheadClienteConta.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(2);
    }

    @Test
    void recusaDoCircuitoMeioAbertoEReconhecida() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());
        circuito.transitionToOpenState();
        circuito.transitionToHalfOpenState();
        assertThat(circuito.tryAcquirePermission()).as("a única sonda do meio-aberto").isTrue();

        assertThatThrownBy(() -> resiliencia.executar(false, () -> "recusada"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(resiliencia.recusadaNoMeioAberto(e)).isTrue());
    }

    @Test
    void recusaComCircuitoAbertoNaoEDoMeioAberto() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults());
        circuito.transitionToOpenState();

        assertThatThrownBy(() -> resiliencia.executar(false, () -> "recusada"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(resiliencia.recusadaNoMeioAberto(e)).isFalse());
        assertThat(resiliencia.recusadaNoMeioAberto(new IllegalStateException())).isFalse();
    }

    private static void ocupar(Bulkhead alvo) {
        while (alvo.tryAcquirePermission()) {
            // ocupa todas as vagas
        }
    }

    private ResilienciaFontePrimaria resiliencia(RateLimiterConfig limite) {
        return new ResilienciaFontePrimaria(retry, circuito, RateLimiter.of("teste", limite), bulkhead,
                bulkheadClienteConta);
    }
}
