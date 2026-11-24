package br.com.consultas.operacoescredito;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;

import java.util.HashMap;
import java.util.Map;

/**
 * Stubs registrados no WireMock da fonte primária durante o teste, pela API de administração (os stubs
 * gerados em {@code wiremock/mappings} não são editados à mão). Operações fictícias 9xxxxxxxx, que não existem
 * na massa, para não interferir em outros testes do mesmo contexto.
 */
final class GatewayMainframeSimulado {

    /** Página única sem ocorrências: {@code 000} + {@code N} + chave de continuação em branco (ADR-0016). */
    static final String PAGINA_VAZIA = "000N" + " ".repeat(20);

    private final RestClient admin;

    GatewayMainframeSimulado(GenericContainer<?> wireMock) {
        this.admin = RestClient.create(FontePrimariaContainers.urlWireMock(wireMock));
    }

    /** Prefixo das rotas do gateway de uma custódia, ex.: {@code /mainframe/custodias/SF}. */
    static String custodia(String custodia) {
        return "/mainframe/custodias/" + custodia;
    }

    /** Rota de um bloco de uma operação numa custódia. */
    static String bloco(String custodia, long id, String bloco) {
        return custodia(custodia) + "/operacoes/" + id + "/blocos/" + bloco;
    }

    /** Padrão das rotas de todos os blocos de uma operação, em qualquer custódia. */
    static String blocos(long id) {
        return "/mainframe/custodias/[A-Z0-9]+/operacoes/" + id + "/blocos/.*";
    }

    /**
     * Blocos {@code operacao}, {@code originacao} e {@code parcelas} (vazio) válidos de uma operação fictícia na
     * custódia SF; nas outras custódias, sem stub, o WireMock responde 404 (a operação não está nelas).
     */
    void registrarOperacao(long id, int atrasoMs) {
        registrarOperacao("SF", id, atrasoMs);
    }

    /** Idem, numa custódia (layout base: SF, F5, NSF; a OD tem um campo extra no bloco operacao). */
    void registrarOperacao(String custodia, long id, int atrasoMs) {
        var base = custodia(custodia) + "/operacoes/" + id + "/blocos/";
        registrar(Map.of("method", "GET", "url", base + "operacao"),
                resposta(200, "%018dATIVA          2026-01-01          70000000159   ".formatted(id), atrasoMs));
        registrar(Map.of("method", "GET", "url", base + "originacao"),
                resposta(200, "%018dMOBILE              004  ".formatted(id), atrasoMs));
        registrar(Map.of("method", "GET", "url", base + "parcelas"), resposta(200, PAGINA_VAZIA, atrasoMs));
    }

    /** Página com as ocorrências já formatadas e, se {@code proxima} não for nula, a chave da próxima. */
    static String pagina(int quantidade, String proxima, String ocorrencias) {
        return "%03d".formatted(quantidade) + (proxima == null ? "N" : "S")
                + "%-20s".formatted(proxima == null ? "" : proxima) + ocorrencias;
    }

    /** Stub de uma URL exata; devolve o id do mapeamento. */
    String registrar(String url, int status, String corpo, int atrasoMs) {
        return registrar(Map.of("method", "GET", "url", url), resposta(status, corpo, atrasoMs));
    }

    /** Stub de todas as URLs que casam com o padrão, com prioridade sobre os stubs de URL exata. */
    String registrarPadrao(String urlPathPattern, int status, String corpo, int atrasoMs) {
        var mapeamento = new HashMap<String, Object>();
        mapeamento.put("priority", 1);
        mapeamento.put("request", Map.of("method", "GET", "urlPathPattern", urlPathPattern));
        mapeamento.put("response", resposta(status, corpo, atrasoMs));
        return enviar(mapeamento);
    }

    /** Stub de uma página seguinte ({@code chave_continuacao}) de uma rota, com prioridade sobre os gerados. */
    String registrarPagina(String urlPath, String chaveContinuacao, int status, String corpo) {
        var mapeamento = new HashMap<String, Object>();
        mapeamento.put("priority", 1);
        mapeamento.put("request", Map.of("method", "GET", "urlPath", urlPath,
                "queryParameters", Map.of("chave_continuacao", Map.of("equalTo", chaveContinuacao))));
        mapeamento.put("response", resposta(status, corpo, 0));
        return enviar(mapeamento);
    }

    /** Passo de um cenário do WireMock: no estado {@code estado} responde e passa para {@code proximoEstado}. */
    void registrarPassoDoCenario(String cenario, String estado, String proximoEstado, String url, int status,
                                 String corpo) {
        var mapeamento = new HashMap<String, Object>();
        mapeamento.put("scenarioName", cenario);
        mapeamento.put("requiredScenarioState", estado);
        if (proximoEstado != null) {
            mapeamento.put("newScenarioState", proximoEstado);
        }
        mapeamento.put("priority", 1);
        mapeamento.put("request", Map.of("method", "GET", "url", url));
        mapeamento.put("response", resposta(status, corpo, 0));
        enviar(mapeamento);
    }

    void remover(String idMapeamento) {
        admin.delete().uri("/__admin/mappings/{id}", idMapeamento).retrieve().toBodilessEntity();
    }

    /**
     * Requisições recebidas que casam com o padrão. O WireMock registra cada requisição no journal depois de
     * responder, então a contagem só vale quando para de mudar.
     */
    int contar(String urlPathPattern) {
        var anterior = -1;
        var atual = contarAgora(urlPathPattern);
        while (atual != anterior) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return atual;
            }
            anterior = atual;
            atual = contarAgora(urlPathPattern);
        }
        return atual;
    }

    private int contarAgora(String urlPathPattern) {
        var resposta = admin.post().uri("/__admin/requests/count")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("method", "GET", "urlPathPattern", urlPathPattern))
                .retrieve()
                .body(Map.class);
        return ((Number) resposta.get("count")).intValue();
    }

    private String registrar(Map<String, Object> requisicao, Map<String, Object> resposta) {
        return enviar(Map.of("request", requisicao, "response", resposta));
    }

    private String enviar(Map<String, Object> mapeamento) {
        var criado = admin.post().uri("/__admin/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapeamento)
                .retrieve()
                .body(Map.class);
        return (String) criado.get("id");
    }

    private static Map<String, Object> resposta(int status, String corpo, int atrasoMs) {
        var resposta = new HashMap<String, Object>(Map.of("status", status, "body", corpo,
                "headers", Map.of("Content-Type", "text/plain; charset=utf-8")));
        if (atrasoMs > 0) {
            resposta.put("fixedDelayMilliseconds", atrasoMs);
        }
        return resposta;
    }
}
