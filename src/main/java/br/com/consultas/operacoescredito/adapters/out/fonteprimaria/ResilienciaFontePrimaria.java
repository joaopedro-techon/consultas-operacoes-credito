package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Proteção própria da fonte primária, isolada da visão consolidada: cada chamada ao gateway passa, de fora para
 * dentro, por retry → circuit breaker → rate limiter → bulkhead (a mesma ordem das anotações do Resilience4j).
 * Assim cada tentativa conta no circuito, e circuito aberto, rate limiter e bulkhead recusam sem retry.
 * Os números vêm de {@code resilience4j.*.instances.}{@value #NOME} no {@code application.yml}; a
 * classificação das falhas, de {@link FalhasDoGateway}.
 */
final class ResilienciaFontePrimaria {

    /** Nome das instâncias do Resilience4j (e valor da tag {@code name} das métricas {@code resilience4j.*}). */
    static final String NOME = "fontePrimaria";

    private static final Duration TENTE_NOVAMENTE_SOBRECARGA = Duration.ofSeconds(1);

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;

    ResilienciaFontePrimaria(Retry retry, CircuitBreaker circuitBreaker, RateLimiter rateLimiter, Bulkhead bulkhead) {
        this.retry = Objects.requireNonNull(retry, "retry");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
    }

    /**
     * Executa a chamada protegida. Recusa da proteção (sem chamar o gateway) vira
     * {@link FonteDeOperacoesIndisponivelException}; falhas da chamada saem como vieram, depois do retry.
     */
    <T> T executar(Supplier<T> chamada) {
        var protegida = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        RateLimiter.decorateSupplier(rateLimiter,
                                Bulkhead.decorateSupplier(bulkhead, chamada))));
        try {
            return protegida.get();
        } catch (CallNotPermittedException e) {
            throw new FonteDeOperacoesIndisponivelException(
                    "Circuit breaker da fonte primária aberto", esperaComCircuitoAberto(), e);
        } catch (RequestNotPermitted e) {
            throw new FonteDeOperacoesIndisponivelException("Rate limiter da fonte primária sem permissões",
                    rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod(), e);
        } catch (BulkheadFullException e) {
            throw new FonteDeOperacoesIndisponivelException("Bulkhead da fonte primária cheio",
                    TENTE_NOVAMENTE_SOBRECARGA, e);
        }
    }

    private Duration esperaComCircuitoAberto() {
        return Duration.ofMillis(circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    }
}
