package br.com.consultas.operacoescredito.config.observabilidade;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WARN de transição de circuito e recusas (amostradas), inclusive em instâncias criadas depois. */
@ExtendWith(OutputCaptureExtension.class)
class EventosDeResilienciaConfigTest {

    private final EventosDeResilienciaConfig config = new EventosDeResilienciaConfig();

    @Test
    void transicoesDoCircuitoSaoLogadasComAsTaxas(CapturedOutput saida) {
        var circuitos = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults(),
                List.of(config.logDeEventosDoCircuitBreaker()));
        var circuito = circuitos.circuitBreaker("dynamodb");

        circuito.transitionToOpenState();
        circuito.transitionToHalfOpenState();

        assertThat(saida.getOut())
                .contains("Circuit breaker dynamodb mudou de fechado (CLOSED) para aberto (OPEN)")
                .contains("taxa de falhas")
                .contains("mudou de aberto (OPEN) para meio-aberto (HALF_OPEN)");
    }

    @Test
    void recusasDoBulkheadSaoAmostradas(CapturedOutput saida) {
        var bulkheads = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO).build(), List.of(config.logDeEventosDoBulkhead()));
        var bulkhead = bulkheads.bulkhead("dynamodb-cliente-conta");
        bulkhead.acquirePermission();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(bulkhead::acquirePermission).isInstanceOf(BulkheadFullException.class);
        }

        var linhas = saida.getOut().lines()
                .filter(linha -> linha.contains("Bulkhead dynamodb-cliente-conta cheio")).toList();
        assertThat(linhas).as("uma linha por instância a cada 5 s").hasSize(1);
    }
}
