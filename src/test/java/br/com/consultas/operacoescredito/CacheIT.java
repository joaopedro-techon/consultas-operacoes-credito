package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cache por bloco no Valkey observado pela API HTTP (ADR-0002, seção 6 da especificação).
 * <p>
 * Para saber de onde veio cada bloco, o teste altera o item no DynamoDB depois da primeira chamada: bloco que
 * volta com o conteúdo antigo veio do cache; com o novo, foi lido do DynamoDB. O Valkey é inspecionado direto
 * pela porta do container; a aplicação fala com ele por um Toxiproxy, que o teste derruba ou atrasa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import({VisaoConsolidadaContainers.class, CacheContainers.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheIT {

    private static final String ROTA = "/v1/operacoes-credito";
    private static final String HEADER_INDISPONIVEIS = "x-blocos-indisponiveis";
    private static final String TODOS_OS_BLOCOS =
            "componentes,parcelas,amortizacoes,recebimentos,plano,saldo,controles_iof";
    private static final String TABELA = "operacoes-credito";
    private static final String PK = "numero_operacao";
    private static final String SK = "custodia_expand";
    private static final long TTL_PADRAO_MS = Duration.ofMinutes(5).toMillis();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestTestClient client;
    private final DynamoDbClient dynamoDb;
    private final Proxy proxyValkey;
    private final CircuitBreakerRegistry circuitBreakers;
    private final LettuceConnectionFactory conexoesDaAplicacao;
    private final RedisClient valkeyClient;
    private final StatefulRedisConnection<byte[], byte[]> valkey;
    /** Itens do DynamoDB alterados pelo teste, com o original (vazio: não existia), restaurados ao final. */
    private final Map<Map<String, AttributeValue>, Optional<Map<String, AttributeValue>>> alterados =
            new LinkedHashMap<>();

    @Autowired
    CacheIT(RestTestClient client, DynamoDbClient dynamoDb, Proxy proxyValkey, CircuitBreakerRegistry circuitBreakers,
            LettuceConnectionFactory conexoesDaAplicacao, @Qualifier("valkey") GenericContainer<?> valkeyContainer) {
        this.client = client;
        this.dynamoDb = dynamoDb;
        this.proxyValkey = proxyValkey;
        this.circuitBreakers = circuitBreakers;
        this.conexoesDaAplicacao = conexoesDaAplicacao;
        this.valkeyClient = RedisClient.create(CacheContainers.uriDireta(valkeyContainer));
        this.valkey = valkeyClient.connect(ByteArrayCodec.INSTANCE);
    }

    @BeforeEach
    void valkeyDisponivelEVazio() throws IOException, InterruptedException {
        // Gravações no cache saem fora da requisição: as do teste anterior terminam (ou desistem no timeout de
        // comando, 10 ms) antes de limpar o Valkey
        Thread.sleep(100);
        restaurarValkey();
        valkey.sync().flushall();
    }

    /** Proxy sem toxics e ligado, conexão da aplicação refeita e circuito {@code valkey} fechado. */
    private void restaurarValkey() throws IOException {
        for (var toxic : proxyValkey.toxics().getAll()) {
            toxic.remove();
        }
        proxyValkey.enable();
        // Reconexão imediata (sem esperar o backoff do Lettuce) depois de um teste com o Valkey fora
        conexoesDaAplicacao.resetConnection();
        circuitBreakers.circuitBreaker("valkey").reset();
    }

    @AfterEach
    void restaurarDynamoDb() {
        alterados.forEach((chave, original) -> original.ifPresentOrElse(
                item -> dynamoDb.putItem(b -> b.tableName(TABELA).item(item)),
                () -> dynamoDb.deleteItem(b -> b.tableName(TABELA).key(chave))));
        alterados.clear();
    }

    @AfterAll
    void fecharValkey() throws IOException {
        // O contexto (com o proxy) é reaproveitado por outras classes com os mesmos containers, como o
        // LogDeAcessoComCacheIT: o Valkey não pode ficar fora depois do último teste
        restaurarValkey();
        valkey.close();
        valkeyClient.shutdown();
    }

    @Test
    void segundaChamadaLeDoDynamoDbSoOBlocoAusente() {
        var primeira = dados(consultarOperacao("100000004", "parcelas,amortizacoes"));
        aguardarNoCache("op:100000004:operacao", "op:100000004:originacao", "op:100000004:parcelas",
                "op:100000004:amortizacoes");

        // Depois da primeira chamada, tudo muda no DynamoDB: só o que não estava em cache pode vir novo
        gravarBloco(100000004L, "ORIGINACAO", "{\"id_operacao\":100000004,\"canal_contratacao\":\"ALTERADO\"}");
        gravarBloco(100000004L, "PARCELAS", "[]");
        gravarBloco(100000004L, "SALDO", "{\"id_operacao\":100000004,\"saldo_devedor_operacao\":1.23}");
        var segunda = dados(consultarOperacao("100000004", "parcelas,saldo"));

        assertThat(segunda.get("operacao")).isEqualTo(primeira.get("operacao"));
        assertThat(segunda.get("originacao")).isEqualTo(primeira.get("originacao"));
        assertThat(segunda.get("parcelas")).isEqualTo(primeira.get("parcelas"));
        assertThat(segunda.get("parcelas")).isNotEmpty();
        assertThat(segunda.get("saldo")).isEqualTo(JSON.readTree(
                "{\"id_operacao\":100000004,\"saldo_devedor_operacao\":1.23}"));
        // O bloco lido agora também passa a ser servido do cache
        aguardarNoCache("op:100000004:saldo");
        gravarBloco(100000004L, "SALDO", "{\"id_operacao\":100000004,\"saldo_devedor_operacao\":9.99}");
        assertThat(dados(consultarOperacao("100000004", "saldo")).get("saldo"))
                .isEqualTo(segunda.get("saldo"));
    }

    @Test
    void cacheGuardaPorOperacaoEBlocoOGzipComoVeioDoDynamoDbComTtl() {
        consultarOperacao("100000001", TODOS_OS_BLOCOS);
        // 9 blocos + a custódia da operação
        await().atMost(Duration.ofSeconds(5)).until(() -> valkey.sync().dbsize() == 10);

        for (var bloco : List.of("operacao", "originacao", "componentes", "parcelas", "amortizacoes", "recebimentos",
                "plano", "saldo", "controles_iof")) {
            var chave = chave("op:100000001:" + bloco);
            assertThat(valkey.sync().get(chave)).as(bloco)
                    .isEqualTo(textoDetalheNoDynamoDb(100000001L, bloco.toUpperCase()));
            assertThat(valkey.sync().pttl(chave)).as(bloco).isBetween(TTL_PADRAO_MS - 60_000, TTL_PADRAO_MS);
        }
        assertThat(valkey.sync().get(chave("custodia:100000001"))).asString(StandardCharsets.UTF_8).isEqualTo("SF");
        assertThat(valkey.sync().pttl(chave("custodia:100000001")))
                .isBetween(Duration.ofHours(24).toMillis() - 60_000, Duration.ofHours(24).toMillis());
        assertThat(valkey.sync().dbsize()).isEqualTo(10);
    }

    @Test
    void custodiaEmCacheQueNaoTemMaisAOperacaoEEsquecidaERedescoberta() {
        // A operação "migrou": o cache aponta a F5, mas ela está na SF
        valkey.sync().set(chave("custodia:100000002"), chave("F5"));

        dados(consultarOperacao("100000002", "parcelas"));

        await().atMost(Duration.ofSeconds(5)).until(() ->
                "SF".equals(new String(valkey.sync().get(chave("custodia:100000002")), StandardCharsets.UTF_8)));
    }

    @Test
    void blocoIndisponivelNaoECacheadoEApareceQuandoChegaAoDynamoDb() {
        consultarOperacao("100000003", "plano,saldo")
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo");

        // Os blocos existentes vão na mesma gravação: com eles no cache, a gravação terminou
        aguardarNoCache("op:100000003:operacao", "op:100000003:originacao");
        assertThat(valkey.sync().exists(chave("op:100000003:plano"), chave("op:100000003:saldo"))).isZero();
        gravarBloco(100000003L, "SALDO", "{\"id_operacao\":100000003,\"saldo_devedor_operacao\":10.00}");
        consultarOperacao("100000003", "plano,saldo")
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano")
                .expectBody()
                .jsonPath("$.data[0].saldo.saldo_devedor_operacao").isEqualTo(10.00);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CLIENTE, cpf_cnpj, 11144477735", "CONTA, conta, 434115000088823"})
    void clienteEContaUsamOCacheDosBlocosMasNaoDaListaDeOperacoes(String tipo, String parametro, String valor) {
        var primeira = lista(consultarLista(tipo, parametro, valor, "parcelas"));
        assertThat(primeira).hasSize(1);
        aguardarNoCache("op:100000006:originacao", "op:100000006:parcelas");

        gravarBloco(100000006L, "PARCELAS", "[]");
        var operacaoNova = textoNoDynamoDb(100000006L, "OPERACAO").replace("100000006", "900000006");
        gravarBloco(900000006L, "OPERACAO", operacaoNova, Map.of(
                "cpf_cnpj_cliente", AttributeValue.fromS("11144477735"),
                "conta_cliente", AttributeValue.fromS("434115000088823"),
                "sk_gsi", AttributeValue.fromS("2099-01-01#900000006")));
        gravarBloco(900000006L, "ORIGINACAO",
                textoNoDynamoDb(100000006L, "ORIGINACAO").replace("100000006", "900000006"));
        var segunda = lista(consultarLista(tipo, parametro, valor, "parcelas"));

        // A lista vem do GSI a cada chamada: a operação nova aparece sem esperar expiração
        assertThat(segunda).hasSize(2);
        assertThat(segunda.get(0).get("operacao").get("id_operacao").asLong()).isEqualTo(900000006L);
        assertThat(segunda.get(0).get("parcelas").isNull()).isTrue();
        // Os blocos da operação já consultada vêm do cache
        assertThat(segunda.get(1)).isEqualTo(primeira.get(0));
        aguardarNoCache("op:900000006:originacao");
        assertThat(valkey.sync().exists(chave("op:100000006:operacao"))).as("operacao vem do GSI").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"OPERACAO, id_operacao, 100000001", "CLIENTE, cpf_cnpj, 52998224725", "CONTA, conta, 434115000065874"})
    void valkeyForaNaoAlteraAResposta(String tipo, String parametro, String valor) throws IOException {
        var esperada = consultarLista(tipo, parametro, valor, TODOS_OS_BLOCOS).returnResult(String.class);

        proxyValkey.disable();
        var resposta = consultarLista(tipo, parametro, valor, TODOS_OS_BLOCOS).returnResult(String.class);

        assertThat(resposta.getResponseBody()).isEqualTo(esperada.getResponseBody());
        assertThat(resposta.getResponseHeaders().get(HEADER_INDISPONIVEIS))
                .isEqualTo(esperada.getResponseHeaders().get(HEADER_INDISPONIVEIS));
    }

    @Test
    void valkeyLentoNaoAlteraARespostaNemSegueOAtraso() throws IOException {
        var esperada = consultarOperacao("100000001", TODOS_OS_BLOCOS).returnResult(String.class).getResponseBody();
        valkey.sync().flushall();

        proxyValkey.toxics().latency("valkey-lento", ToxicDirection.DOWNSTREAM, 3_000);
        var inicio = System.nanoTime();
        var resposta = consultarOperacao("100000001", TODOS_OS_BLOCOS).returnResult(String.class).getResponseBody();
        var duracao = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(resposta).isEqualTo(esperada);
        assertThat(duracao).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void comOValkeyForaOCircuitBreakerAbreEAsConsultasParamDeTentarOValkey() throws Exception {
        var indisponiveisAntes = metrica("consultas.cache", "resultado:indisponivel");
        proxyValkey.disable();
        for (int i = 0; i < 10; i++) {
            consultarOperacao("100000001", "parcelas");
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> circuitBreakers.circuitBreaker("valkey").getState()
                == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);
        // Gravações que ainda estavam saindo (fora da requisição) terminam antes da contagem
        Thread.sleep(200);
        var chamadasAoValkey = chamadasAoValkey();

        for (int i = 0; i < 5; i++) {
            consultarOperacao("100000001", "parcelas");
        }

        assertThat(chamadasAoValkey()).isEqualTo(chamadasAoValkey);
        assertThat(metrica("resilience4j.circuitbreaker.state", "name:valkey", "state:open")).isEqualTo(1.0);
        // Valkey fora e circuito aberto não são misses: os 15 blocos procurados contam como indisponíveis
        // (os blocos sempre presentes operacao/originacao e parcelas, por consulta)
        assertThat(metrica("consultas.cache", "resultado:indisponivel") - indisponiveisAntes).isEqualTo(45);
    }

    /** Espera as chaves aparecerem no Valkey: a gravação no cache sai fora da requisição. */
    private void aguardarNoCache(String... chaves) {
        var bytes = java.util.Arrays.stream(chaves).map(CacheIT::chave).toArray(byte[][]::new);
        await().atMost(Duration.ofSeconds(5)).until(() -> valkey.sync().exists(bytes) == chaves.length);
    }

    private RestTestClient.ResponseSpec consultarOperacao(String idOperacao, String expand) {
        return consultarLista("OPERACAO", "id_operacao", idOperacao, expand);
    }

    private RestTestClient.ResponseSpec consultarLista(String tipo, String parametro, String valor, String expand) {
        var resposta = client.get().uri(ROTA + "?" + parametro + "={valor}&expand={expand}", valor, expand)
                .header("x-tipo-consulta", tipo)
                .exchange();
        resposta.expectStatus().isOk();
        return resposta;
    }

    private static JsonNode dados(RestTestClient.ResponseSpec resposta) {
        var operacoes = lista(resposta);
        assertThat(operacoes).hasSize(1);
        return operacoes.getFirst();
    }

    private static List<JsonNode> lista(RestTestClient.ResponseSpec resposta) {
        EntityExchangeResult<String> resultado = resposta.returnResult(String.class);
        var data = JSON.readTree(resultado.getResponseBody()).get("data");
        var operacoes = new ArrayList<JsonNode>();
        data.forEach(operacoes::add);
        return operacoes;
    }

    private void gravarBloco(long idOperacao, String bloco, String json) {
        gravarBloco(idOperacao, bloco, json, Map.of());
    }

    /** Grava (ou sobrescreve) o item do bloco no DynamoDB, guardando o original para restaurar ao final. */
    private void gravarBloco(long idOperacao, String bloco, String json, Map<String, AttributeValue> atributos) {
        var chave = chaveDynamoDb(idOperacao, bloco);
        alterados.computeIfAbsent(chave, k -> {
            var atual = dynamoDb.getItem(b -> b.tableName(TABELA).key(k));
            return atual.hasItem() && !atual.item().isEmpty() ? Optional.of(atual.item()) : Optional.empty();
        });
        var item = new HashMap<>(chave);
        item.put("texto_detalhe", AttributeValue.fromB(SdkBytes.fromByteArray(gzip(json))));
        item.putAll(atributos);
        dynamoDb.putItem(b -> b.tableName(TABELA).item(item));
    }

    private byte[] textoDetalheNoDynamoDb(long idOperacao, String bloco) {
        return dynamoDb.getItem(b -> b.tableName(TABELA).key(chaveDynamoDb(idOperacao, bloco)))
                .item().get("texto_detalhe").b().asByteArray();
    }

    private String textoNoDynamoDb(long idOperacao, String bloco) {
        try (var entrada = new GZIPInputStream(new ByteArrayInputStream(
                textoDetalheNoDynamoDb(idOperacao, bloco)))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, AttributeValue> chaveDynamoDb(long idOperacao, String bloco) {
        return Map.of(PK, AttributeValue.fromN(Long.toString(idOperacao)),
                SK, AttributeValue.fromS(custodia(idOperacao) + "#EXPAND#" + bloco));
    }

    /** Custódia de cada operação da massa (ADR-0012); a 100000006 está na SF e na NSF, e vale a NSF. */
    private static String custodia(long idOperacao) {
        return switch ((int) (idOperacao - 100000000L)) {
            case 3, 7 -> "F5";
            case 4 -> "OD";
            case 5, 6 -> "NSF";
            default -> "SF";
        };
    }

    private static byte[] gzip(String json) {
        var saida = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(saida)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    private static byte[] chave(String chave) {
        return chave.getBytes(StandardCharsets.UTF_8);
    }

    /** Chamadas ao Valkey feitas pela aplicação ({@code consultas.dependencia}, {@code dependencia=valkey}). */
    private double chamadasAoValkey() {
        return metrica("consultas.dependencia", "dependencia:valkey");
    }

    /** Primeira medida (COUNT ou VALUE) da métrica no Actuator; 0 se a métrica ainda não existe. */
    private double metrica(String nome, String... tags) {
        var uri = new StringBuilder("/actuator/metrics/").append(nome);
        for (int i = 0; i < tags.length; i++) {
            uri.append(i == 0 ? '?' : '&').append("tag=").append(tags[i]);
        }
        var resultado = client.get().uri(uri.toString()).exchange().returnResult(String.class);
        if (resultado.getStatus().value() == 404) {
            return 0;
        }
        return JSON.readTree(resultado.getResponseBody()).get("measurements").get(0).get("value").asDouble();
    }
}
