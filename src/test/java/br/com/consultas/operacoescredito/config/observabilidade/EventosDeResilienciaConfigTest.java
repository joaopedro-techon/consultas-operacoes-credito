package br.com.consultas.operacoescredito.config.observabilidade;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WARN de retry, transição de circuito e recusas (amostradas), inclusive em instâncias criadas depois. */
@ExtendWith(OutputCaptureExtension.class)
class EventosDeResilienciaConfigTest {

    private final EventosDeResilienciaConfig config = new EventosDeResilienciaConfig();

    @Test
    void cadaRetryEORetryEsgotadoSaoLogados(CapturedOutput saida) {
        var retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build(),
                List.<RegistryEventConsumer<io.github.resilience4j.retry.Retry>>of(config.logDeEventosDoRetry()));
        var tentativas = new AtomicInteger();

        assertThatThrownBy(() -> retries.retry("fontePrimaria-F5-parcelas").executeSupplier(() -> {
            tentativas.incrementAndGet();
            throw new IllegalStateException("502 do gateway");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tentativas).hasValue(3);
        assertThat(saida.getOut())
                .doesNotContain("tentativa 1 de 3")
                .contains("Retry fontePrimaria-F5-parcelas: tentativa 2 de 3")
                .contains("Retry fontePrimaria-F5-parcelas: tentativa 3 de 3")
                .contains("502 do gateway")
                .contains("Retry fontePrimaria-F5-parcelas esgotado após 3 tentativas");
    }

    @Test
    void transicoesDoCircuitoSaoLogadasComAsTaxas(CapturedOutput saida) {
        var circuitos = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults(),
                List.of(config.logDeEventosDoCircuitBreaker()));
        var circuito = circuitos.circuitBreaker("fontePrimaria-SF-saldo");

        circuito.transitionToOpenState();
        circuito.transitionToHalfOpenState();

        assertThat(saida.getOut())
                .contains("Circuit breaker fontePrimaria-SF-saldo mudou de fechado (CLOSED) para aberto (OPEN)")
                .contains("taxa de falhas")
                .contains("mudou de aberto (OPEN) para meio-aberto (HALF_OPEN)");
    }

    @Test
    void recusasDoBulkheadSaoAmostradas(CapturedOutput saida) {
        var bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO).build(), List.of(config.logDeEventosDoBulkhead()));
        var bulkhead = bulkheads.bulkhead("fontePrimaria-OD");
        bulkhead.acquirePermission();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(bulkhead::acquirePermission).isInstanceOf(BulkheadFullException.class);
        }

        var linhas = saida.getOut().lines().filter(linha -> linha.contains("Bulkhead fontePrimaria-OD cheio")).toList();
        assertThat(linhas).as("uma linha por instância a cada 5 s").hasSize(1);
    }
}
