package br.com.consultas.operacoescredito;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Este serviço só atende a visão consolidada (ADR-0017): uma única fonte de operações e nenhuma propriedade para
 * escolher rotas. A rota da fonte primária é de outro serviço (o ALB a leva para lá) e, aqui, responde 404 em
 * Problem Details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class RotaUnicaIT {

    private final RestTestClient client;
    private final ApplicationContext contexto;

    @Autowired
    RotaUnicaIT(RestTestClient client, ApplicationContext contexto) {
        this.client = client;
        this.contexto = contexto;
    }

    @Test
    void soAVisaoConsolidadaSobe() {
        assertThat(contexto.getBeansOfType(FonteDeOperacoes.class)).hasSize(1);
        assertThat(contexto.containsBean("fontePrimaria")).isFalse();

        client.get().uri("/v1/operacoes-credito?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rotaDaFontePrimariaNaoExisteNesteServico() {
        client.get().uri("/v1/operacoes-credito/fonte-primaria?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);
    }
}
