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
import java.time.Instant;
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
 * Cache versionado no Valkey observado pela API HTTP (ADR-0002, ADR-0018).
 * <p>
 * Para saber de onde veio cada bloco, o teste altera o item no DynamoDB depois da primeira chamada: bloco que
 * volta com o conteúdo antigo veio do cache; com o novo, foi lido do DynamoDB. Alterações "na mesma versão"
 * (mantendo o {@code codigo_identificador_carga}) só servem para isso; uma atualização de verdade muda a versão.
 * O Valkey é inspecionado direto pela porta do container; a aplicação fala com ele por um Toxiproxy, que o teste
 * derruba ou atrasa.
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
    private static final List<String> NOMES_DOS_BLOCOS = List.of("operacao", "originacao", "componentes", "parcelas",
            "amortizacoes", "recebimentos", "plano", "saldo", "controles_iof");
    private static final String TABELA = "operacoes-credito";
    private static final String PK = "numero_operacao";
    private static final String SK = "custodia_expand";
    private static final String VERSAO = "codigo_identificador_carga";
    private static final long TTL_PADRAO_MS = Duration.ofMinutes(5).toMillis();
    private static final long TTL_CUSTODIA_MS = Duration.ofHours(24).toMillis();
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
        aguardarCustodia(100000004L);

        // Depois da primeira chamada, tudo muda no DynamoDB (na mesma versão): só o que não estava em cache vem novo
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
        // O bloco lido agora completa o hash da mesma versão e também passa a ser servido do cache
        aguardarCampos("op:100000004:OD", "saldo");
        gravarBloco(100000004L, "SALDO", "{\"id_operacao\":100000004,\"saldo_devedor_operacao\":9.99}");
        assertThat(dados(consultarOperacao("100000004", "saldo")).get("saldo"))
                .isEqualTo(segunda.get("saldo"));
    }

    @Test
    void cacheGuardaUmHashPorOperacaoECustodiaComAVersaoEOGzipComoVeioDoDynamoDb() {
        consultarOperacao("100000001", TODOS_OS_BLOCOS);
        aguardarCustodia(100000001L);

        var hash = chave("op:100000001:SF");
        var versao = epoch(versaoNoDynamoDb(100000001L, "OPERACAO"));
        assertThat(texto(valkey.sync().hget(hash, chave("versao")))).isEqualTo(Long.toString(versao));
        for (var bloco : NOMES_DOS_BLOCOS) {
            assertThat(valkey.sync().hget(hash, chave(bloco))).as(bloco)
                    .isEqualTo(textoDetalheNoDynamoDb(100000001L, bloco.toUpperCase()));
        }
        assertThat(valkey.sync().pttl(hash)).isBetween(TTL_PADRAO_MS - 60_000, TTL_PADRAO_MS);

        var custodia = chave("custodia-vc:100000001");
        assertThat(texto(valkey.sync().hget(custodia, chave("custodia")))).isEqualTo("SF");
        assertThat(texto(valkey.sync().hget(custodia, chave("versao")))).isEqualTo(Long.toString(versao));
        assertThat(valkey.sync().pttl(custodia)).isBetween(TTL_CUSTODIA_MS - 60_000, TTL_CUSTODIA_MS);
        // Chave compartilhada com a fonte primária (ADR-0017), sem versão
        assertThat(texto(valkey.sync().get(chave("custodia:100000001")))).isEqualTo("SF");
        assertThat(valkey.sync().dbsize()).isEqualTo(3);
    }

    @Test
    void leituraAtrasadaComDadoAntigoNaoSobrescreveOMarcadorDeInvalidacao() throws InterruptedException {
        // O invalidador já soube de uma versão mais nova do que a que está no DynamoDB para esta leitura
        var versaoNova = epoch(versaoNoDynamoDb(100000002L, "OPERACAO")) + 60_000;
        var hash = chave("op:100000002:SF");
        valkey.sync().hset(hash, Map.of(chave("versao"), chave(Long.toString(versaoNova)), chave("marcador"),
                chave("1")));
        valkey.sync().pexpire(hash, TTL_PADRAO_MS);

        var resposta = dados(consultarOperacao("100000002", "parcelas"));

        assertThat(resposta.get("parcelas")).isNotEmpty();
        aguardarCustodia(100000002L);
        // A gravação da versão antiga foi rejeitada: o marcador continua, sem blocos
        assertThat(valkey.sync().hkeys(hash)).map(CacheIT::texto).containsExactlyInAnyOrder("versao", "marcador");
        assertThat(texto(valkey.sync().hget(hash, chave("versao")))).isEqualTo(Long.toString(versaoNova));
    }

    @Test
    void atualizacaoParaVersaoNovaComMarcadorTrocaOHashInteiro() {
        var primeira = dados(consultarOperacao("100000001", "saldo"));
        aguardarCustodia(100000001L);

        // Atualização Online: todos os blocos da operação regravados com a versão nova; o invalidador grava o
        // marcador com ela
        var versaoNova = Instant.parse("2099-01-01T00:00:00.000Z");
        for (var bloco : NOMES_DOS_BLOCOS) {
            var texto = textoNoDynamoDb(100000001L, bloco.toUpperCase());
            gravarBloco(100000001L, bloco.toUpperCase(), bloco.equals("saldo")
                    ? "{\"id_operacao\":100000001,\"saldo_devedor_operacao\":0.01}" : texto, versaoNova.toString());
        }
        var hash = chave("op:100000001:SF");
        valkey.sync().del(hash);
        valkey.sync().hset(hash, Map.of(chave("versao"), chave(Long.toString(versaoNova.toEpochMilli())),
                chave("marcador"), chave("1")));

        var segunda = dados(consultarOperacao("100000001", "saldo"));

        assertThat(segunda.get("saldo")).isNotEqualTo(primeira.get("saldo"));
        assertThat(segunda.get("saldo").get("saldo_devedor_operacao").asDouble()).isEqualTo(0.01);
        await().atMost(Duration.ofSeconds(5)).until(() -> valkey.sync().hexists(hash, chave("saldo")));
        assertThat(valkey.sync().hexists(hash, chave("marcador"))).isFalse();
        assertThat(texto(valkey.sync().hget(hash, chave("versao"))))
                .isEqualTo(Long.toString(versaoNova.toEpochMilli()));
    }

    @Test
    void blocosEmVersoesDiferentesNoDynamoDbViram503() {
        // Só o saldo numa versão nova (uma transação que nunca ficou completa): a consulta não pode misturar
        gravarBloco(100000002L, "SALDO", textoNoDynamoDb(100000002L, "SALDO"), "2099-01-01T00:00:00.000Z");

        client.get().uri(ROTA + "?id_operacao=100000002&expand=saldo")
                .header("x-tipo-consulta", "OPERACAO")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().exists("Retry-After");
        assertThat(valkey.sync().exists(chave("op:100000002:SF"))).isZero();
    }

    @Test
    void custodiaEmCacheQueNaoTemMaisAOperacaoEEsquecidaERedescoberta() {
        // A operação "migrou": o cache aponta a F5 (numa versão antiga), mas ela está na SF
        valkey.sync().hset(chave("custodia-vc:100000002"), Map.of(chave("custodia"), chave("F5"), chave("versao"),
                chave("1")));

        dados(consultarOperacao("100000002", "parcelas"));

        await().atMost(Duration.ofSeconds(5)).until(() -> "SF".equals(
                texto(valkey.sync().hget(chave("custodia-vc:100000002"), chave("custodia")))));
    }

    @Test
    void blocoIndisponivelNaoECacheadoEApareceQuandoChegaAoDynamoDb() {
        consultarOperacao("100000003", "plano,saldo")
                .expectHeader().valueEquals(HEADER_INDISPONIVEIS, "plano,saldo");

        aguardarCustodia(100000003L);
        var hash = chave("op:100000003:F5");
        assertThat(valkey.sync().hexists(hash, chave("operacao"))).isTrue();
        assertThat(valkey.sync().hexists(hash, chave("plano"))).isFalse();
        assertThat(valkey.sync().hexists(hash, chave("saldo"))).isFalse();
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
        aguardarCustodia(100000006L);
        aguardarCampos("op:100000006:NSF", "originacao", "parcelas");

        gravarBloco(100000006L, "PARCELAS", "[]");
        var versao = versaoNoDynamoDb(100000006L, "OPERACAO");
        var operacaoNova = textoNoDynamoDb(100000006L, "OPERACAO").replace("100000006", "900000006");
        gravarBloco(900000006L, "OPERACAO", operacaoNova, versao, Map.of(
                "cpf_cnpj_cliente", AttributeValue.fromS("11144477735"),
                "conta_cliente", AttributeValue.fromS("434115000088823"),
                "sk_gsi", AttributeValue.fromS("2099-01-01#900000006")));
        gravarBloco(900000006L, "ORIGINACAO",
                textoNoDynamoDb(100000006L, "ORIGINACAO").replace("100000006", "900000006"), versao, Map.of());
        var segunda = lista(consultarLista(tipo, parametro, valor, "parcelas"));

        // A lista vem do GSI a cada chamada: a operação nova aparece sem esperar expiração
        assertThat(segunda).hasSize(2);
        assertThat(segunda.get(0).get("operacao").get("id_operacao").asLong()).isEqualTo(900000006L);
        assertThat(segunda.get(0).get("parcelas").isNull()).isTrue();
        // Os blocos da operação já consultada vêm do cache
        assertThat(segunda.get(1)).isEqualTo(primeira.get(0));
        aguardarCampos("op:900000006:NSF", "originacao");
        assertThat(valkey.sync().hexists(chave("op:100000006:NSF"), chave("operacao")))
                .as("operacao vem do GSI").isFalse();
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

    /** Espera a custódia aparecer no Valkey: ela é gravada fora da requisição, depois dos blocos. */
    private void aguardarCustodia(long idOperacao) {
        await().atMost(Duration.ofSeconds(5)).until(() ->
                valkey.sync().exists(chave("custodia-vc:" + idOperacao)) == 1);
    }

    /** Espera os campos aparecerem no hash: a gravação no cache sai fora da requisição. */
    private void aguardarCampos(String hash, String... campos) {
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            for (var campo : campos) {
                if (!valkey.sync().hexists(chave(hash), chave(campo))) {
                    return false;
                }
            }
            return true;
        });
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

    /** Grava o bloco na mesma versão do registro do bloco {@code OPERACAO} da operação. */
    private void gravarBloco(long idOperacao, String bloco, String json) {
        gravarBloco(idOperacao, bloco, json, versaoNoDynamoDb(idOperacao, "OPERACAO"), Map.of());
    }

    private void gravarBloco(long idOperacao, String bloco, String json, String versao) {
        gravarBloco(idOperacao, bloco, json, versao, Map.of());
    }

    /** Grava (ou sobrescreve) o item do bloco no DynamoDB, guardando o original para restaurar ao final. */
    private void gravarBloco(long idOperacao, String bloco, String json, String versao,
                             Map<String, AttributeValue> atributos) {
        var chave = chaveDynamoDb(idOperacao, bloco);
        alterados.computeIfAbsent(chave, k -> {
            var atual = dynamoDb.getItem(b -> b.tableName(TABELA).key(k));
            return atual.hasItem() && !atual.item().isEmpty() ? Optional.of(atual.item()) : Optional.empty();
        });
        var item = new HashMap<>(chave);
        var original = alterados.get(chave);
        original.ifPresent(anterior -> anterior.forEach((atributo, valor) -> {
            if (!atributo.equals("texto_detalhe")) {
                item.put(atributo, valor);
            }
        }));
        item.put("texto_detalhe", AttributeValue.fromB(SdkBytes.fromByteArray(gzip(json))));
        item.put(VERSAO, AttributeValue.fromS(versao));
        item.putAll(atributos);
        dynamoDb.putItem(b -> b.tableName(TABELA).item(item));
    }

    private Map<String, AttributeValue> itemNoDynamoDb(long idOperacao, String bloco) {
        return dynamoDb.getItem(b -> b.tableName(TABELA).key(chaveDynamoDb(idOperacao, bloco)).consistentRead(true))
                .item();
    }

    private String versaoNoDynamoDb(long idOperacao, String bloco) {
        return itemNoDynamoDb(idOperacao, bloco).get(VERSAO).s();
    }

    private byte[] textoDetalheNoDynamoDb(long idOperacao, String bloco) {
        return itemNoDynamoDb(idOperacao, bloco).get("texto_detalhe").b().asByteArray();
    }

    private String textoNoDynamoDb(long idOperacao, String bloco) {
        try (var entrada = new GZIPInputStream(new ByteArrayInputStream(
                textoDetalheNoDynamoDb(idOperacao, bloco)))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long epoch(String versao) {
        return Instant.parse(versao).toEpochMilli();
    }

    private static Map<String, AttributeValue> chaveDynamoDb(long idOperacao, String bloco) {
        return Map.of(PK, AttributeValue.fromN(Long.toString(idOperacao)),
                SK, AttributeValue.fromS(custodia(idOperacao) + "#EXPAND#" + bloco));
    }

    /** Custódia de cada operação da massa (ADR-0012); a 100000006 está na SF e na NSF, e vale a NSF. */
    private static String custodia(long idOperacao) {
        return switch ((int) (idOperacao % 100000000L)) {
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

    private static String texto(byte[] valor) {
        return valor == null ? null : new String(valor, StandardCharsets.UTF_8);
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
