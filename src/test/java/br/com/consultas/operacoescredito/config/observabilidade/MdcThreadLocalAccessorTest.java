package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O MDC chega às virtual threads filhas quando a tarefa é envolvida por Micrometer Context
 * Propagation (como fará o fan-out), e não vaza para a thread depois da tarefa.
 */
class MdcThreadLocalAccessorTest {

    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void mdcDaRequisicaoChegaAVirtualThreadFilha() throws Exception {
        MDC.put("correlation_id", "abc-123");
        MDC.put("consumidor", "sistema-custodia");

        var tarefa = snapshots.captureAll().wrap(MDC::getCopyOfContextMap);

        assertThat(emVirtualThread(tarefa))
                .containsEntry("correlation_id", "abc-123")
                .containsEntry("consumidor", "sistema-custodia");
    }

    @Test
    void semPropagacaoAVirtualThreadNaoHerdaOMdc() throws Exception {
        MDC.put("correlation_id", "abc-123");

        assertThat(emVirtualThread(MDC::getCopyOfContextMap)).isNullOrEmpty();
    }

    @Test
    void mdcDaThreadFilhaEhRestauradoAoFimDaTarefa() throws Exception {
        MDC.put("correlation_id", "da-requisicao");
        var tarefa = snapshots.captureAll().wrap(() -> MDC.get("correlation_id"));

        try (var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())) {
            executor.submit(() -> MDC.put("correlation_id", "anterior-da-thread")).get();
            assertThat(executor.submit(tarefa).get()).isEqualTo("da-requisicao");
            assertThat(executor.submit(() -> MDC.get("correlation_id")).get()).isEqualTo("anterior-da-thread");
        }
    }

    private static Map<String, String> emVirtualThread(java.util.concurrent.Callable<Map<String, String>> tarefa)
            throws InterruptedException, ExecutionException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(tarefa).get();
        }
    }
}
