package br.com.consultas.operacoescredito;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fan-out da fonte primária em CLIENTE/CONTA com massas próprias registradas no WireMock durante o teste
 * (operações 9100000xx, que não existem na visão consolidada): teto de chamadas simultâneas por requisição,
 * falha de qualquer chamada derrubando a requisição inteira e correlationId em toda chamada filha.
 * Contexto próprio, com teto e timeout de resposta baixos, e sem LocalStack (só a fonte primária é usada).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "consultas.fonte-primaria.fan-out.max-chamadas-simultaneas=" + FontePrimariaFanOutIT.TETO,
        "consultas.fonte-primaria.pool.timeout-resposta=400ms"})
@AutoConfigureRestTestClient
@Import(FontePrimariaContainers.class)
class FontePrimariaFanOutIT {

    static final int TETO = 3;

    private static final String ROTA = "/v1/operacoes-credito/fonte-primaria";
    private static final String HEADER_CORRELATION_ID = "x-correlationid";

    private final RestTestClient client;
    private final GenericContainer<?> wireMock;

    @Autowired
    FontePrimariaFanOutIT(RestTestClient client, @Qualifier("wireMock") GenericContainer<?> wireMock) {
        this.client = client;
        this.wireMock = wireMock;
    }

    @Test
    void fanOutRespeitaOTetoDeChamadasSimultaneasPorRequisicao() {
        var ids = List.of(910000001L, 910000002L, 910000003L, 910000004L);
        registrarLista("/mainframe/clientes/70000000159/operacoes", ids, "2026-01-01");
        ids.forEach(id -> registrarOperacao(id, 150));
        var correlationId = "fp-teto-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000159&expand=parcelas", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(4);

        var chamadas = chamadasDeBloco(correlationId);
        assertThat(chamadas).hasSize(4 * 3);
        var simultaneas = maximoDeChamadasSimultaneas(chamadas);
        assertThat(simultaneas).as("chamadas simultâneas ao gateway").isLessThanOrEqualTo(TETO).isGreaterThan(1);
    }

    @Test
    void erro5xxEmUmaOperacaoDoFanOutDerrubaARequisicaoInteiraCom502() {
        var ids = List.of(910000011L, 910000012L, 910000013L);
        registrarLista("/mainframe/clientes/70000000230/operacoes", ids, "2026-02-01");
        ids.forEach(id -> registrarOperacao(id, 0));
        registrarStub("/mainframe/operacoes/910000012/blocos/parcelas", 500, "erro interno no mainframe", 0);

        consultar("CLIENTE", "cpf_cnpj=70000000230&expand=parcelas", "fp-5xx-" + UUID.randomUUID())
                .expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.data").doesNotExist()
                .jsonPath("$.correlation_id").exists()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).doesNotContain("erro interno"));
    }

    @Test
    void timeoutEmUmaOperacaoDoFanOutDerrubaARequisicaoInteiraCom504SemEsperarAsDemais() {
        var ids = List.of(910000021L, 910000022L, 910000023L);
        registrarLista("/mainframe/contas/700000000000002/operacoes", ids, "2026-03-01");
        ids.forEach(id -> registrarOperacao(id, 0));
        registrarStub("/mainframe/operacoes/910000022/blocos/parcelas", 200, "000", 3000);

        var inicio = System.nanoTime();
        consultar("CONTA", "conta=700000000000002&expand=parcelas", "fp-timeout-" + UUID.randomUUID())
                .expectStatus().isEqualTo(504)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(504)
                .jsonPath("$.data").doesNotExist()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail)
                        .contains("limite_operacoes", "expand", "OPERACAO"));
        var duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        assertThat(duracaoMs).as("falha no prazo de resposta, sem esperar o atraso do gateway").isLessThan(2500);
    }

    @Test
    void erroNaListaDeOperacoesDevolve502() {
        registrarStub("/mainframe/contas/700000000000003/operacoes", 503, "indisponivel", 0);

        consultar("CONTA", "conta=700000000000003", "fp-lista-5xx-" + UUID.randomUUID())
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Fonte de dados indisponível");
    }

    @Test
    void listaDeOperacoesForaDoLayoutDevolve502() {
        registrarStub("/mainframe/contas/700000000000004/operacoes", 200, "002000000000910000031", 0);

        consultar("CONTA", "conta=700000000000004", "fp-lista-invalida-" + UUID.randomUUID())
                .expectStatus().isEqualTo(502);
    }

    @Test
    void correlationIdChegaATodasAsChamadasFilhasDoFanOut() {
        var ids = List.of(910000041L, 910000042L, 910000043L, 910000044L, 910000045L);
        registrarLista("/mainframe/clientes/70000000400/operacoes", ids, "2026-04-01");
        ids.forEach(id -> registrarOperacao(id, 0));
        var correlationId = "fp-filhas-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000400&expand=parcelas", correlationId)
                .expectStatus().isOk()
                .expectHeader().valueEquals(HEADER_CORRELATION_ID, correlationId);

        var rotas = "/mainframe/(clientes/70000000400/operacoes|operacoes/91000004[1-5]/blocos/.*)";
        assertThat(contar(rotas, Map.of())).isEqualTo(1 + 5 * 3);
        assertThat(contar(rotas, Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .isEqualTo(1 + 5 * 3);
    }

    @Test
    void operacaoListadaQueNaoExisteMaisNaFontePrimariaFicaForaDaResposta() {
        registrarLista("/mainframe/clientes/70000000582/operacoes", List.of(910000052L, 910000051L), "2026-05-01");
        registrarOperacao(910000051L, 0);

        consultar("CLIENTE", "cpf_cnpj=70000000582&expand=parcelas", "fp-sumiu-" + UUID.randomUUID())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(910000051));
    }

    @Test
    void limiteOperacoesPegaAsMaisRecentesMesmoComAListaForaDeOrdem() {
        registrarStub("/mainframe/clientes/70000000663/operacoes", 200, "003"
                + registroDaLista(910000061L, "2026-01-10")
                + registroDaLista(910000062L, "2026-06-10")
                + registroDaLista(910000063L, "2026-03-10"), 0);
        List.of(910000061L, 910000062L, 910000063L).forEach(id -> registrarOperacao(id, 0));
        var correlationId = "fp-ordem-" + UUID.randomUUID();

        consultar("CLIENTE", "cpf_cnpj=70000000663&limite_operacoes=2", correlationId)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[*].operacao.id_operacao").isEqualTo(List.of(910000062, 910000063));

        assertThat(contar("/mainframe/operacoes/910000061/.*",
                Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId)))).isZero();
    }

    private RestTestClient.ResponseSpec consultar(String tipo, String query, String correlationId) {
        return client.get().uri(ROTA + "?" + query)
                .header("x-tipo-consulta", tipo)
                .header(HEADER_CORRELATION_ID, correlationId)
                .exchange();
    }

    /** Lista de operações do gateway, mais recente primeiro (as datas decrescem um dia por operação). */
    private void registrarLista(String rota, List<Long> ids, String dataMaisRecente) {
        var trancode = new StringBuilder("%03d".formatted(ids.size()));
        var data = java.time.LocalDate.parse(dataMaisRecente);
        for (var id : ids) {
            trancode.append(registroDaLista(id, data.toString()));
            data = data.minusDays(1);
        }
        registrarStub(rota, 200, trancode.toString(), 0);
    }

    private static String registroDaLista(long id, String data) {
        return "%018d%s".formatted(id, data);
    }

    /** Blocos {@code operacao}, {@code originacao} e {@code parcelas} (vazio) de uma operação fictícia. */
    private void registrarOperacao(long id, int atrasoMs) {
        var base = "/mainframe/operacoes/" + id + "/blocos/";
        registrarStub(base + "operacao", 200, "%018dATIVA          2026-01-01          70000000159   ".formatted(id), atrasoMs);
        registrarStub(base + "originacao", 200, "%018dMOBILE              004  ".formatted(id), atrasoMs);
        registrarStub(base + "parcelas", 200, "000", atrasoMs);
    }

    private void registrarStub(String url, int status, String corpo, int atrasoMs) {
        var resposta = new HashMap<String, Object>(Map.of("status", status, "body", corpo,
                "headers", Map.of("Content-Type", "text/plain; charset=utf-8")));
        if (atrasoMs > 0) {
            resposta.put("fixedDelayMilliseconds", atrasoMs);
        }
        adminWireMock().post().uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("request", Map.of("method", "GET", "url", url), "response", resposta))
                .retrieve()
                .toBodilessEntity();
    }

    private int contar(String urlPathPattern, Map<String, Object> headers) {
        var resposta = adminWireMock().post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", urlPathPattern, "headers", headers))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }

    /** Chamadas de bloco (não a lista) com o correlationId: início e fim no relógio do WireMock, em ms. */
    @SuppressWarnings("unchecked")
    private List<long[]> chamadasDeBloco(String correlationId) {
        var resposta = adminWireMock().post().uri("/__admin/requests/find")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", "/mainframe/operacoes/.*",
                        "headers", Map.of(HEADER_CORRELATION_ID, Map.of("equalTo", correlationId))))
                .retrieve()
                .body(Map.class);
        var intervalos = new ArrayList<long[]>();
        for (var requisicao : (List<Map<String, Object>>) resposta.get("requests")) {
            var inicio = ((Number) requisicao.get("loggedDate")).longValue();
            // O WireMock segura cada resposta pelo atraso fixo: a chamada ocupa ao menos [inicio, inicio + 150)
            intervalos.add(new long[] {inicio, inicio + 150});
        }
        return intervalos;
    }

    /** Maior número de intervalos {@code [inicio, fim)} abertos ao mesmo tempo. */
    private static int maximoDeChamadasSimultaneas(List<long[]> intervalos) {
        var maximo = 0;
        for (var intervalo : intervalos) {
            var abertos = (int) intervalos.stream()
                    .filter(outro -> outro[0] <= intervalo[0] && intervalo[0] < outro[1])
                    .count();
            maximo = Math.max(maximo, abertos);
        }
        return maximo;
    }

    private RestClient adminWireMock() {
        return RestClient.create(FontePrimariaContainers.urlWireMock(wireMock));
    }
}
