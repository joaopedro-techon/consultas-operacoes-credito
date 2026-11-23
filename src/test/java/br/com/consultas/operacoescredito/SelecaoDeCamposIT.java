package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Testes de caixa-preta do {@code fields} (seleção de campos) na visão consolidada.
 * Valores esperados vêm da massa em {@code localstack/massa} (regras em {@code localstack/gerar_massa.py}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class SelecaoDeCamposIT {

    private static final String ROTA = "/v1/operacoes-credito";

    private final RestTestClient client;

    @Autowired
    SelecaoDeCamposIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void blocoObjetoVemSoComOsCamposPedidosMaisIdOperacaoEBlocoSemCamposVemCompleto() {
        consultar("100000001", "", "operacao.data_contratacao_operacao,operacao.situacao_operacao_credito")
                .expectStatus().isOk()
                .expectBody().json("""
                        {"data": [{
                          "operacao": {
                            "id_operacao": 100000001,
                            "situacao_operacao_credito": "ATIVA",
                            "data_contratacao_operacao": "2026-05-21"
                          },
                          "originacao": {
                            "id_operacao": 100000001,
                            "canal_contratacao": "MOBILE",
                            "dependencia_contabil": "004"
                          }
                        }]}
                        """, JsonCompareMode.STRICT);
    }

    private RestTestClient.ResponseSpec consultar(String idOperacao, String expand, String fields) {
        return client.get().uri(ROTA + "?id_operacao={id}&expand={expand}&fields={fields}", idOperacao, expand, fields)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
