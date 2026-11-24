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
 * Serviço ECS da visão consolidada (ADR-0015): com {@code consultas.rotas-ativas=visao-consolidada}, nada do
 * mainframe sobe (sem gateway, pool, resiliência nem layouts) e a rota da fonte primária responde 404. Sem
 * WireMock no contexto: se algo da fonte primária subisse, a aplicação não teria gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "consultas.rotas-ativas=visao-consolidada")
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class RotasAtivasIT {

    private final RestTestClient client;
    private final ApplicationContext contexto;

    @Autowired
    RotasAtivasIT(RestTestClient client, ApplicationContext contexto) {
        this.client = client;
        this.contexto = contexto;
    }

    @Test
    void soAVisaoConsolidadaSobeEAFontePrimariaResponde404() {
        assertThat(contexto.getBeansOfType(FonteDeOperacoes.class)).hasSize(1);
        assertThat(contexto.containsBean("fontePrimaria")).isFalse();

        client.get().uri("/v1/operacoes-credito?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk();
        client.get().uri("/v1/operacoes-credito/fonte-primaria?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
