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
 * dentro, por retry → circuit breaker → rate limiter → bulkhead {@value #BULKHEAD_CLIENTE_CONTA} (só nas consultas
 * CLIENTE/CONTA) → bulkhead {@value #NOME} (a mesma ordem das anotações do Resilience4j). Assim cada tentativa
 * conta no circuito, e circuito aberto, rate limiter e bulkheads recusam sem retry (e sem contar no circuito).
 * O bulkhead de CLIENTE/CONTA é menor que o total: o fan-out das consultas mais caras não ocupa todas as vagas e
 * sempre sobra lugar para OPERACAO (o mesmo desenho da visão consolidada, ADR-0007).
 * Os números vêm de {@code resilience4j.*.instances} no {@code application.yml}; a classificação das falhas, de
 * {@link FalhasDoGateway}.
 */
final class ResilienciaFontePrimaria {

    /** Nome das instâncias do Resilience4j (e valor da tag {@code name} das métricas {@code resilience4j.*}). */
    static final String NOME = "fontePrimaria";
    /** Bulkhead das chamadas das consultas CLIENTE e CONTA. */
    static final String BULKHEAD_CLIENTE_CONTA = "fontePrimaria-cliente-conta";

    private static final Duration TENTE_NOVAMENTE_SOBRECARGA = Duration.ofSeconds(1);

    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final Bulkhead bulkheadClienteConta;

    ResilienciaFontePrimaria(Retry retry, CircuitBreaker circuitBreaker, RateLimiter rateLimiter, Bulkhead bulkhead,
                             Bulkhead bulkheadClienteConta) {
        this.retry = Objects.requireNonNull(retry, "retry");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
        this.bulkheadClienteConta = Objects.requireNonNull(bulkheadClienteConta, "bulkheadClienteConta");
    }

    /**
     * Executa a chamada protegida. Recusa da proteção (sem chamar o gateway) vira
     * {@link FonteDeOperacoesIndisponivelException}; falhas da chamada saem como vieram, depois do retry.
     *
     * @param clienteConta {@code true} nas chamadas de uma consulta CLIENTE ou CONTA (lista e blocos)
     */
    <T> T executar(boolean clienteConta, Supplier<T> chamada) {
        Supplier<T> limitada = Bulkhead.decorateSupplier(bulkhead, chamada);
        if (clienteConta) {
            limitada = Bulkhead.decorateSupplier(bulkheadClienteConta, limitada);
        }
        var protegida = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                        RateLimiter.decorateSupplier(rateLimiter, limitada)));
        try {
            return protegida.get();
        } catch (CallNotPermittedException e) {
            throw new FonteDeOperacoesIndisponivelException(
                    "Circuit breaker da fonte primária aberto", esperaComCircuitoAberto(), e);
        } catch (RequestNotPermitted e) {
            throw new FonteDeOperacoesIndisponivelException("Rate limiter da fonte primária sem permissões",
                    rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod(), e);
        } catch (BulkheadFullException e) {
            throw new FonteDeOperacoesIndisponivelException("Bulkhead da fonte primária cheio: " + e.getMessage(),
                    TENTE_NOVAMENTE_SOBRECARGA, e);
        }
    }

    /**
     * {@code true} se {@code erro} é a recusa de uma chamada porque o circuito está meio-aberto e já deu todas as
     * permissões: as chamadas em andamento são as sondas dele e devem terminar para que o circuito decida.
     */
    boolean recusadaNoMeioAberto(Throwable erro) {
        return erro instanceof FonteDeOperacoesIndisponivelException
                && erro.getCause() instanceof CallNotPermittedException
                && circuitBreaker.getState() == CircuitBreaker.State.HALF_OPEN;
    }

    private Duration esperaComCircuitoAberto() {
        return Duration.ofMillis(circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    }
}
