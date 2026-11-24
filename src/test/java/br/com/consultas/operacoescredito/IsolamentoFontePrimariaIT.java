package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lentidão e falha do mainframe não afetam a rota da visão consolidada: com dezenas de consultas presas na
 * fonte primária (pool e bulkhead dela tomados), a visão consolidada continua respondendo 200 rápido.
 * Mesma configuração do {@link FontePrimariaIT} para reaproveitar o contexto (LocalStack + WireMock); a
 * degradação fica restrita a operações fictícias e os stubs são removidos ao final.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import({VisaoConsolidadaContainers.class, FontePrimariaContainers.class})
class IsolamentoFontePrimariaIT {

    private static final int CONSULTAS_PRESAS = 30;
    private static final long PRIMEIRA_OPERACAO = 930000001L;

    private final GatewayMainframeSimulado gateway;
    private final List<String> stubs = new ArrayList<>();

    @LocalServerPort
    private int porta;

    @Autowired
    IsolamentoFontePrimariaIT(@Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.gateway = new GatewayMainframeSimulado(wireMock);
    }

    @AfterEach
    void removerDegradacao() {
        stubs.forEach(gateway::remover);
    }

    @Test
    void visaoConsolidadaSegueSaudavelEnquantoOMainframeEstaLentoEFalhando() throws Exception {
        var api = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
        assertThat(statusDaVisaoConsolidada(api)).as("aquecimento").isEqualTo(200);
        // Mainframe degradado em todas as custódias: lento (3 s, acima do timeout de resposta de 700 ms) e, quando
        // responde, com 500
        stubs.add(gateway.registrarPadrao("/mainframe/custodias/[A-Z0-9]+/operacoes/93[0-9]{7}/blocos/.*", 500,
                "lento", 3000));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var presas = new ArrayList<Future<Integer>>();
            for (int i = 0; i < CONSULTAS_PRESAS; i++) {
                var id = PRIMEIRA_OPERACAO + i;
                presas.add(executor.submit(() -> status(() -> api.get()
                        .uri("/v1/operacoes-credito/fonte-primaria?id_operacao={id}&expand=parcelas", id)
                        .header("x-tipo-consulta", "OPERACAO")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())));
            }
            Thread.sleep(300); // as consultas à fonte primária já estão presas no mainframe

            for (int i = 0; i < 5; i++) {
                var inicio = System.nanoTime();
                var status = statusDaVisaoConsolidada(api);
                var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;
                assertThat(status).isEqualTo(200);
                assertThat(duracaoMs).as("visão consolidada sem esperar o mainframe").isLessThan(800);
            }
            assertThat(presas).as("fonte primária ainda degradada durante as consultas à visão consolidada")
                    .anyMatch(presa -> !presa.isDone());

            for (var presa : presas) {
                assertThat(resultado(presa)).as("fonte primária degradada: 503 (custódias sem resposta, bulkhead) ou 504 (prazo)")
                        .isIn(503, 504);
            }
        }
    }

    private static int statusDaVisaoConsolidada(RestClient api) {
        return status(() -> api.get()
                .uri("/v1/operacoes-credito?id_operacao=100000001&expand=parcelas")
                .header("x-tipo-consulta", "OPERACAO")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value());
    }

    private static int status(java.util.function.IntSupplier chamada) {
        try {
            return chamada.getAsInt();
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private static int resultado(Future<Integer> presa) throws InterruptedException, ExecutionException {
        return presa.get();
    }
}
