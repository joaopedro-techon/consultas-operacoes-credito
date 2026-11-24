package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de caixa-preta das consultas CLIENTE e CONTA na fonte primária, com a massa do WireMock gerada da
 * mesma massa do DynamoDB: a rota da fonte primária deve devolver o mesmo JSON da visão consolidada.
 * Mesma configuração de contexto do {@link FontePrimariaIT} (o contexto e os containers são reaproveitados).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@AutoConfigureTracing
@Import({VisaoConsolidadaContainers.class, FontePrimariaContainers.class})
class FontePrimariaClienteContaIT {

    private static final String ROTA_VISAO_CONSOLIDADA = "/v1/operacoes-credito";
    private static final String ROTA_FONTE_PRIMARIA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final String HEADER_CORRELATION_ID = "x-correlationid";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";
    /** Cliente (e conta) com 14 operações mensais, de 100000101 (mais antiga) a 100000114. */
    private static final String CNPJ_14_OPERACOES = "11222333000181";
    private static final String CONTA_14_OPERACOES = "434115000055501";

    private final RestTestClient client;
    private final GenericContainer<?> wireMock;

    @Autowired
    FontePrimariaClienteContaIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.wireMock = wireMock;
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', textBlock = """
            CLIENTE | cpf_cnpj=52998224725&expand=TODOS
            CLIENTE | cpf_cnpj=52998224725
            CLIENTE | cpf_cnpj=12ABC34501DE35&expand=TODOS
            CLIENTE | cpf_cnpj=11144477735&expand=TODOS
            CLIENTE | cpf_cnpj=11222333000181&expand=TODOS
            CLIENTE | cpf_cnpj=52998224725&expand=saldo,plano&limite_operacoes=2
            CLIENTE | cpf_cnpj=11222333000181&expand=parcelas&limite_operacoes=5
            CLIENTE | cpf_cnpj=11222333000181&limite_operacoes=500
            CONTA   | conta=434115000065874&expand=TODOS
            CONTA   | conta=434115000099991&expand=saldo,plano
            CONTA   | conta=434115000077712&expand=saldo&fields=operacao.data_contratacao_operacao,saldo.saldo_devedor_operacao
            CONTA   | conta=434115000055501&expand=TODOS&limite_operacoes=3
            CLIENTE | cpf_cnpj=52998224725&filter=operacao.situacao_operacao_credito==ATIVA
            CLIENTE | cpf_cnpj=52998224725&expand=parcelas&filter=parcelas.situacao_parcela==ABERTA
            CLIENTE | cpf_cnpj=39053344705
            CLIENTE | cpf_cnpj=98XYZ76501AB46&expand=parcelas
            CONTA   | conta=999999999
            """)
    void clienteOuContaNaFontePrimariaDevolveOMesmoJsonEHeaderDaVisaoConsolidada(String tipo, String query) {
        var uri = "?" + query.replace("expand=TODOS", "expand=" + TODOS_OS_BLOCOS);

        var fontePrimaria = resposta(ROTA_FONTE_PRIMARIA + uri, tipo);
        var visaoConsolidada = resposta(ROTA_VISAO_CONSOLIDADA + uri, tipo);

        assertThat(fontePrimaria.getResponseBody()).isEqualTo(visaoConsolidada.getResponseBody());
        assertThat(fontePrimaria.getResponseHeaders().get(HEADER_INDISPONIVEIS))
                .isEqualTo(visaoConsolidada.getResponseHeaders().get(HEADER_INDISPONIVEIS));
    }

    @Test
    void consultaPorClienteNaFontePrimariaDevolveAsOperacoesDaMaisRecenteParaAMaisAntiga() {
        consultar("CLIENTE", "cpf_cnpj=52998224725&expand=saldo,plano", "fp-cliente-ordem-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo")
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000003, 100000001, 100000002))
                .jsonPath("$.data[*].operacao.data_contratacao_operacao")
                .isEqualTo(List.of("2026-10-28", "2026-05-21", "2025-09-15"))
                .jsonPath("$.data[0].saldo").isEmpty()
                .jsonPath("$.data[1].saldo.saldo_devedor_operacao").isEqualTo(3956.00);
    }

    @Test
    void clienteOuContaSemOperacoesNaFontePrimariaDevolveListaVazia() {
        consultar("CONTA", "conta=999999999&expand=parcelas", "fp-conta-vazia-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HEADER_INDISPONIVEIS)
                .expectBody().json("""
                        {"data": []}
                        """, JsonCompareMode.STRICT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/mainframe/clientes/" + CNPJ_14_OPERACOES + "/operacoes",
            "/mainframe/contas/" + CONTA_14_OPERACOES + "/operacoes",
            "/mainframe/clientes/52998224725/operacoes",
            "/mainframe/contas/434115000077712/operacoes"})
    void listaDeOperacoesSimuladaNoGatewayVemDaMaisRecenteParaAMaisAntiga(String rota) {
        var trancode = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock)).get().uri(rota)
                .retrieve()
                .body(String.class);

        var operacoes = lerListaDeOperacoes(trancode);
        assertThat(operacoes).hasSizeGreaterThanOrEqualTo(2);
        assertThat(operacoes).isSortedAccordingTo(
                Comparator.comparing(OperacaoListada::dataContratacao).reversed()
                        .thenComparing(Comparator.comparingLong(OperacaoListada::idOperacao).reversed()));
    }

    @Test
    void limiteOperacoesEhAplicadoAntesDoFanOut() {
        var correlationId = "fp-limite-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=" + CNPJ_14_OPERACOES + "&expand=parcelas&limite_operacoes=3", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(100000114, 100000113, 100000112));

        // Uma chamada à lista + 3 operações × (operacao, originacao, parcelas); nenhuma das outras 11 operações
        assertThat(chamadasAoGateway("/mainframe/clientes/.*", correlationId)).isEqualTo(1);
        assertThat(chamadasAoGateway("/mainframe/operacoes/.*", correlationId)).isEqualTo(9);
        assertThat(chamadasAoGateway("/mainframe/operacoes/10000011[234]/blocos/.*", correlationId)).isEqualTo(9);
    }

    @Test
    void semLimiteOFanOutDetalhaTodasAsOperacoesDaContaComCadaBlocoPedido() {
        var correlationId = "fp-sem-limite-" + UUID.randomUUID();

        consultar("CONTA", "conta=" + CONTA_14_OPERACOES + "&expand=saldo", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(14);

        assertThat(chamadasAoGateway("/mainframe/contas/.*", correlationId)).isEqualTo(1);
        assertThat(chamadasAoGateway("/mainframe/operacoes/.*/blocos/(operacao|originacao|saldo)", correlationId))
                .isEqualTo(14 * 3);
        assertThat(chamadasAoGateway("/mainframe/operacoes/.*", correlationId)).isEqualTo(14 * 3);
    }

    @ParameterizedTest(name = "{0} {1} -> 400 igual à visão consolidada")
    @CsvSource(delimiter = '|', textBlock = """
            CLIENTE | cpf_cnpj=52998224726
            CLIENTE | cpf_cnpj=12ABC34501DE36
            CLIENTE | cpf_cnpj=12abc34501de35
            CLIENTE | cpf_cnpj=11111111111
            CLIENTE | cpf_cnpj=529.982.247-25
            CLIENTE | cpf_cnpj=
            CLIENTE | conta=434115000065874
            CLIENTE | cpf_cnpj=52998224725&id_operacao=100000001
            CLIENTE | cpf_cnpj=52998224725&limite_operacoes=0
            CLIENTE | cpf_cnpj=52998224725&limite_operacoes=501
            CLIENTE | cpf_cnpj=52998224725&limite_operacoes=abc
            CONTA   | conta=4341150000658A4
            CONTA   | conta=-1
            CONTA   | cpf_cnpj=52998224725
            CONTA   | conta=434115000065874&limite_operacoes=501
            CONTA   | conta=434115000065874&expand=garantias
            CONTA   | conta=434115000065874&fields=parcelas.situacao_parcela
            CONTA   | conta=434115000065874&filter=operacao.campo_inexistente==1
            """)
    void validacaoDaFontePrimariaEhIdenticaAVisaoConsolidadaESemChamarOGateway(String tipo, String query) {
        var correlationId = "fp-validacao-" + UUID.randomUUID();

        var fontePrimaria = respostaComErro(ROTA_FONTE_PRIMARIA + "?" + query, tipo, correlationId);
        var visaoConsolidada = respostaComErro(ROTA_VISAO_CONSOLIDADA + "?" + query, tipo, correlationId);

        assertThat(fontePrimaria.getStatus().value()).isEqualTo(400);
        assertThat(fontePrimaria.getResponseHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(semCampoInstance(fontePrimaria.getResponseBody()))
                .isEqualTo(semCampoInstance(visaoConsolidada.getResponseBody()));
        assertThat(chamadasAoGateway("/mainframe/.*", correlationId)).isZero();
    }

    private RestTestClient.ResponseSpec consultar(String tipo, String query, String correlationId) {
        return client.get().uri(ROTA_FONTE_PRIMARIA + "?" + query)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange();
    }

    private EntityExchangeResult<String> resposta(String uri, String tipo) {
        return client.get().uri(uri)
                .header("x-tipo-consulta", tipo)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class);
    }

    private EntityExchangeResult<String> respostaComErro(String uri, String tipo, String correlationId) {
        return client.get().uri(uri)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange()
                .returnResult(String.class);
    }

    /** O {@code instance} do Problem Details é a URI chamada, que difere entre as rotas. */
    private static String semCampoInstance(String problema) {
        return problema.replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    /** Requisições recebidas pelo gateway simulado com o correlationId informado (journal do WireMock). */
    private int chamadasAoGateway(String urlPathPattern, String correlationId) {
        var resposta = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock))
                .post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", urlPathPattern,
                        "headers", Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }

    /** Lê o trancode da lista: 3 dígitos de quantidade + registros de 18 (id) + 10 (data) posições. */
    private static List<OperacaoListada> lerListaDeOperacoes(String trancode) {
        var quantidade = Integer.parseInt(trancode.substring(0, 3));
        assertThat(trancode).hasSize(3 + quantidade * 28);
        var operacoes = new ArrayList<OperacaoListada>();
        for (int i = 0; i < quantidade; i++) {
            var registro = trancode.substring(3 + i * 28, 3 + (i + 1) * 28);
            operacoes.add(new OperacaoListada(Long.parseLong(registro.substring(0, 18)), registro.substring(18)));
        }
        return operacoes;
    }

    private record OperacaoListada(long idOperacao, String dataContratacao) {
    }
}
