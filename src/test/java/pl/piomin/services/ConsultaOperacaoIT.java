package pl.piomin.services;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Testes de caixa-preta da consulta OPERACAO na visão consolidada: só a API HTTP é observada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class ConsultaOperacaoIT {

    private static final String ROTA = "/v1/operacoes-credito";

    private final RestTestClient client;

    @Autowired
    ConsultaOperacaoIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void consultaPorOperacaoDevolveOsBlocosOperacaoEOriginacao() {
        client.get().uri(ROTA + "?id_operacao=100000001")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().json("""
                        {"data": [{
                          "operacao": {
                            "id_operacao": 100000001,
                            "situacao_operacao_credito": "ATIVA",
                            "data_contratacao_operacao": "2026-11-21",
                            "data_baixa_operacao": null,
                            "cpf_cnpj": "52998224725"
                          },
                          "originacao": {
                            "id_operacao": 100000001,
                            "canal_contratacao": "MOBILE",
                            "dependencia_contabil": "004"
                          }
                        }]}
                        """, JsonCompareMode.STRICT);
    }
}
