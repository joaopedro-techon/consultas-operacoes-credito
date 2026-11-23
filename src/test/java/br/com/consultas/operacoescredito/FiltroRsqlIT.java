package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;

/**
 * Testes de caixa-preta do {@code filter} (RSQL) na consulta OPERACAO da visão consolidada.
 * Valores esperados vêm da massa em {@code localstack/massa} (regras em {@code localstack/gerar_massa.py}):
 * a operação 100000001 tem 12 parcelas, 1 a 5 LIQUIDADA e 6 a 12 ABERTA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class FiltroRsqlIT {

    private static final String ROTA = "/v1/operacoes-credito";

    private final RestTestClient client;

    @Autowired
    FiltroRsqlIT(RestTestClient client) {
        this.client = client;
    }

    @Test
    void filtroEmParcelasMantemSoAsParcelasQueAtendemAoCriterio() {
        consultar("100000001", "parcelas",
                "parcelas.numero_parcela_amortizacao=ge=4;parcelas.situacao_parcela==ABERTA")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].parcelas[*].numero_parcela_amortizacao")
                .isEqualTo(List.of(6, 7, 8, 9, 10, 11, 12));
    }

    private RestTestClient.ResponseSpec consultar(String idOperacao, String expand, String filter) {
        return client.get().uri(ROTA + "?id_operacao={id}&expand={expand}&filter={filter}", idOperacao, expand, filter)
                .header("x-tipo-consulta", "OPERACAO")
                .exchange();
    }
}
