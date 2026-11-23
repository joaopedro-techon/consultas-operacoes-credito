package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;

/**
 * Testes de caixa-preta das consultas CLIENTE e CONTA na visão consolidada.
 * Valores esperados vêm da massa em {@code localstack/massa} (especificação em {@code localstack/gerar_massa.py}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class ConsultaClienteContaIT {

    private static final String ROTA = "/v1/operacoes-credito";

    private final RestTestClient client;

    @Autowired
    ConsultaClienteContaIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void consultaPorCpfDevolveTodasAsOperacoesDoClienteDaMaisRecenteParaAMaisAntiga() {
        consultar("CLIENTE", "cpf_cnpj=52998224725")
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[*].operacao.data_contratacao_operacao")
                .isEqualTo(List.of("2026-10-28", "2026-05-21", "2025-09-15"))
                .jsonPath("$.data[*].originacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[1].originacao.canal_contratacao").isEqualTo("MOBILE")
                .jsonPath("$.data[1].length()").isEqualTo(2);
    }

    private RestTestClient.ResponseSpec consultar(String tipoConsulta, String query) {
        return client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipoConsulta)
                .exchange();
    }
}
