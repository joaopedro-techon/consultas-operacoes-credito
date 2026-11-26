package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Métrica de capacidade consumida com o cliente real do SDK, contra um servidor HTTP local que responde como o
 * DynamoDB: o interceptor pede {@code ReturnConsumedCapacity=TOTAL} e registra o que volta, por operação e índice.
 */
class CapacidadeConsumidaInterceptorTest {

    private HttpServer servidor;
    private final List<String> corposRecebidos = new CopyOnWriteArrayList<>();
    private volatile String resposta;
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();
    private DynamoDbClient cliente;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/", troca -> {
            corposRecebidos.add(new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var corpo = resposta.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
            troca.sendResponseHeaders(200, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
        var properties = new DynamoDbProperties("t", "sa-east-1", "http://localhost:" + servidor.getAddress().getPort(),
                new DynamoDbProperties.Credenciais("a", "b"), "gsi-cliente", "gsi-conta", Duration.ofSeconds(5),
                Duration.ofSeconds(2), 4,
                new DynamoDbProperties.Pool(10, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new DynamoDbProperties.Retry(0, Duration.ofMillis(1), Duration.ofMillis(5)));
        cliente = DynamoDbConfig.construirCliente(properties, ObservationRegistry.NOOP, metricas);
    }

    @AfterEach
    void derrubarServidor() {
        cliente.close();
        servidor.stop(0);
    }

    @Test
    void queryNaTabelaBasePedeERegistraACapacidadeSemIndice() {
        resposta = "{\"Items\":[],\"Count\":0,\"ConsumedCapacity\":{\"TableName\":\"t\",\"CapacityUnits\":0.5}}";

        cliente.query(b -> b.tableName("t").keyConditionExpression("#pk = :id")
                .expressionAttributeNames(Map.of("#pk", "numero_operacao"))
                .expressionAttributeValues(Map.of(":id", AttributeValue.fromN("1"))));

        assertThat(corposRecebidos).singleElement().asString().contains("\"ReturnConsumedCapacity\":\"TOTAL\"");
        assertThat(capacidade("Query", "nenhum")).isEqualTo(0.5);
    }

    @Test
    void queryNoGsiRegistraOIndice() {
        resposta = "{\"Items\":[],\"Count\":0,\"ConsumedCapacity\":{\"TableName\":\"t\",\"CapacityUnits\":1.0}}";

        cliente.query(b -> b.tableName("t").indexName("gsi-cliente").keyConditionExpression("#c = :v")
                .expressionAttributeNames(Map.of("#c", "cpf_cnpj_cliente"))
                .expressionAttributeValues(Map.of(":v", AttributeValue.fromS("52998224725"))));

        assertThat(capacidade("Query", "gsi-cliente")).isEqualTo(1.0);
    }

    @Test
    void batchGetItemPedeERegistraACapacidade() {
        resposta = "{\"Responses\":{\"t\":[]},\"UnprocessedKeys\":{},"
                + "\"ConsumedCapacity\":[{\"TableName\":\"t\",\"CapacityUnits\":4.5}]}";

        cliente.batchGetItem(b -> b.requestItems(Map.of("t", KeysAndAttributes.builder()
                .keys(Map.of("numero_operacao", AttributeValue.fromN("1"),
                        "custodia_expand", AttributeValue.fromS("SF#EXPAND#OPERACAO")))
                .build())));

        assertThat(corposRecebidos).singleElement().asString().contains("\"ReturnConsumedCapacity\":\"TOTAL\"");
        assertThat(capacidade("BatchGetItem", "nenhum")).isEqualTo(4.5);
    }

    @Test
    void respostaSemCapacidadeInformadaNaoRegistraNada() {
        resposta = "{\"Items\":[],\"Count\":0}";

        cliente.query(b -> b.tableName("t").keyConditionExpression("#pk = :id")
                .expressionAttributeNames(Map.of("#pk", "numero_operacao"))
                .expressionAttributeValues(Map.of(":id", AttributeValue.fromN("1"))));

        assertThat(metricas.find(CapacidadeConsumidaInterceptor.METRICA).summary()).isNull();
    }

    private double capacidade(String operacao, String indice) {
        return metricas.get(CapacidadeConsumidaInterceptor.METRICA)
                .tag("operacao", operacao)
                .tag("indice", indice)
                .summary()
                .totalAmount();
    }
}
