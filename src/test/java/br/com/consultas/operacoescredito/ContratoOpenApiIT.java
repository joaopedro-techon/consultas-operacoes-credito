package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformidade das respostas reais da rota da visão consolidada com {@code contrato/openapi.yaml} (ADR-0017):
 * sucesso nos três tipos de consulta, com {@code expand}, {@code fields}, {@code filter} e blocos indisponíveis, e
 * os erros 400, 404 e 502. Os 503 e 504 são verificados no {@link ResilienciaVisaoConsolidadaIT}, que provoca as
 * falhas do DynamoDB. Valores da massa em {@code localstack/massa}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(VisaoConsolidadaContainers.class)
class ContratoOpenApiIT {

    private static final String ROTA = "/v1/operacoes-credito";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";
    /** Fora da massa: o item corrompido gravado por um teste não aparece para os outros. */
    private static final String OPERACAO_CORROMPIDA = "900000019";

    private final RestTestClient client;
    private final DynamoDbClient dynamoDb;

    @Autowired
    ContratoOpenApiIT(RestTestClient client, DynamoDbClient dynamoDb) {
        this.client = client;
        this.dynamoDb = dynamoDb;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "OPERACAO | id_operacao=100000001&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000004&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000006&expand=" + TODOS_OS_BLOCOS,
            "OPERACAO | id_operacao=100000007&expand=parcelas,controles_iof",
            "OPERACAO | id_operacao=100000001&expand=saldo,plano"
                    + "&fields=operacao.situacao_operacao_credito,saldo.saldo_parcelas.saldo_devedor",
            "OPERACAO | id_operacao=100000001&expand=parcelas&filter=parcelas.situacao_parcela==ABERTA",
            "CLIENTE  | cpf_cnpj=52998224725&expand=" + TODOS_OS_BLOCOS,
            "CLIENTE  | cpf_cnpj=12ABC34501DE35&limite_operacoes=1&expand=parcelas",
            "CLIENTE  | cpf_cnpj=11222333000181&expand=" + TODOS_OS_BLOCOS,
            "CONTA    | conta=434115000065874&expand=saldo",
            "CONTA    | conta=434115000055501&limite_operacoes=5"})
    void consultaComOperacoesEstaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseBody()).contains("\"operacao\"");
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void blocoIndisponivelNuloComOHeaderEstaConforme() {
        // 100000003 (F5) não tem plano nem saldo na visão consolidada, nem dependencia_contabil
        var resposta = consultar("OPERACAO", "id_operacao=100000003&expand=" + TODOS_OS_BLOCOS);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseHeaders().getFirst("x-blocos-indisponiveis")).isEqualTo("plano,saldo");
        ContratoOpenApi.assertConforme(resposta);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "CLIENTE  | cpf_cnpj=12345678909",
            "OPERACAO | id_operacao=100000001&filter=operacao.situacao_operacao_credito==LIQUIDADA"})
    void consultaSemOperacoesEstaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(200);
        assertThat(resposta.getResponseBody()).isEqualTo("{\"data\":[]}");
        ContratoOpenApi.assertConforme(resposta);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "-        | id_operacao=100000001",
            "TODAS    | id_operacao=100000001",
            "OPERACAO | id_operacao=abc",
            "OPERACAO | id_operacao=100000001&cpf_cnpj=52998224725",
            "OPERACAO | id_operacao=100000001&expand=parcelas&expand=saldo",
            "OPERACAO | id_operacao=100000001&expand=garantias",
            "OPERACAO | id_operacao=100000001&fields=parcelas.situacao_parcela",
            "OPERACAO | id_operacao=100000001&filter=operacao.situacao_operacao_credito=~ATIVA",
            "CLIENTE  | cpf_cnpj=52998224700",
            "CONTA    | conta=12a",
            "CONTA    | conta=434115000065874&limite_operacoes=501"})
    void requisicaoInvalidaDevolveProblemaConforme(String tipo, String query) {
        var resposta = consultar(tipo, query);

        assertThat(resposta.getStatus().value()).isEqualTo(400);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void correlationIdInvalidoDevolveProblemaConforme() {
        var resposta = consultar("OPERACAO", "id_operacao=100000001",
                headers -> headers.header("x-correlationid", "com espaço"));

        assertThat(resposta.getStatus().value()).isEqualTo(400);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void operacaoInexistenteDevolveProblemaConforme() {
        var resposta = consultar("OPERACAO", "id_operacao=999999999");

        assertThat(resposta.getStatus().value()).isEqualTo(404);
        ContratoOpenApi.assertConforme(resposta);
    }

    @Test
    void itemForaDoFormatoNaVisaoConsolidadaDevolveProblemaConforme() {
        var chave = Map.of("numero_operacao", AttributeValue.fromN(OPERACAO_CORROMPIDA),
                "custodia_expand", AttributeValue.fromS("SF#EXPAND#OPERACAO"));
        var item = new HashMap<>(chave);
        // texto_detalhe que não é GZIP
        item.put("texto_detalhe", AttributeValue.fromB(SdkBytes.fromString("nao-e-gzip", StandardCharsets.UTF_8)));
        dynamoDb.putItem(b -> b.tableName("operacoes-credito").item(item));
        try {
            var resposta = consultar("OPERACAO", "id_operacao=" + OPERACAO_CORROMPIDA);

            assertThat(resposta.getStatus().value()).isEqualTo(502);
            ContratoOpenApi.assertConforme(resposta);
        } finally {
            dynamoDb.deleteItem(b -> b.tableName("operacoes-credito").key(chave));
        }
    }

    private EntityExchangeResult<String> consultar(String tipo, String query) {
        return consultar(tipo, query, headers -> { });
    }

    private EntityExchangeResult<String> consultar(String tipo, String query,
                                                   Consumer<RestTestClient.RequestHeadersSpec<?>> headers) {
        var requisicao = client.get().uri(ROTA + "?" + query);
        if (tipo != null) {
            requisicao.header("x-tipo-consulta", tipo);
        }
        headers.accept(requisicao);
        return requisicao.exchange().returnResult(String.class);
    }
}
