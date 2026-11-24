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
            .recordException(FalhasDoGateway::falhaDaDependencia)
            .ignoreException(FalhasDoGateway::ignoradaPeloCircuito)
            .build());
    private final Retry retry = Retry.of("teste", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(1))
            .retryOnException(FalhasDoGateway::retentavel)
            .build());

    @Test
    void retentaFalha5xxAteDarCerto() {
        var tentativas = new AtomicInteger();
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults(), BulkheadConfig.ofDefaults());

        var resultado = resiliencia.executar(() -> {
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
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults(), BulkheadConfig.ofDefaults());

        assertThatThrownBy(() -> resiliencia.executar(() -> {
            tentativas.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalida", null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(tentativas).hasValue(1);
    }

    @Test
    void circuitoAbertoViraIndisponivelComOTempoDeEsperaDoCircuito() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults(), BulkheadConfig.ofDefaults());
        circuito.transitionToOpenState();
        var tentativas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.executar(tentativas::incrementAndGet))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(7)));
        assertThat(tentativas).as("circuito aberto não chama nem retenta").hasValue(0);
    }

    @Test
    void rateLimiterSemPermissaoViraIndisponivelComOPeriodoDoLimite() {
        var resiliencia = resiliencia(RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(30))
                .timeoutDuration(Duration.ZERO)
                .build(), BulkheadConfig.ofDefaults());
        resiliencia.executar(() -> "primeira");

        assertThatThrownBy(() -> resiliencia.executar(() -> "segunda"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void bulkheadCheioViraIndisponivel() {
        var resiliencia = resiliencia(RateLimiterConfig.ofDefaults(), BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());

        assertThatThrownBy(() -> resiliencia.executar(() -> resiliencia.executar(() -> "aninhada")))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(1)));
    }

    private ResilienciaFontePrimaria resiliencia(RateLimiterConfig limite, BulkheadConfig bulkhead) {
        return new ResilienciaFontePrimaria(retry, circuito, RateLimiter.of("teste", limite),
                Bulkhead.of("teste", bulkhead));
    }
}
