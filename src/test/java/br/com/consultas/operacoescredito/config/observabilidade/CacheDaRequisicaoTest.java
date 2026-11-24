package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CacheDaRequisicaoTest {

    @AfterEach
    void limpar() {
        CacheDaRequisicao.encerrar();
    }

    @Test
    void contaHitsEMissesPorBlocoNaRequisicao() {
        var contagem = CacheDaRequisicao.iniciar();

        CacheDaRequisicao.hit("parcelas");
        CacheDaRequisicao.hit("parcelas");
        CacheDaRequisicao.miss("saldo");

        assertThat(contagem.hits()).isEqualTo(Map.of("parcelas", 2L));
        assertThat(contagem.misses()).isEqualTo(Map.of("saldo", 1L));
    }

    @Test
    void foraDeUmaRequisicaoRegistrarNaoFazNada() {
        CacheDaRequisicao.hit("parcelas");

        assertThat(CacheDaRequisicao.atual()).isNull();
    }

    @Test
    void threadsFilhasRegistramNaContagemDaRequisicao() throws Exception {
        var contagem = CacheDaRequisicao.iniciar();
        var snapshots = ContextSnapshotFactory.builder().contextRegistry(ContextRegistry.getInstance()).build();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                executor.submit(snapshots.captureAll().wrap(() -> CacheDaRequisicao.miss("parcelas")));
            }
        }

        assertThat(contagem.misses()).isEqualTo(Map.of("parcelas", 20L));
    }

    @Test
    void encerrarRemoveAContagemDaThread() {
        CacheDaRequisicao.iniciar();

        CacheDaRequisicao.encerrar();

        assertThat(CacheDaRequisicao.atual()).isNull();
    }
}
