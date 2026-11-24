package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cliente do SDK contra um servidor HTTP local que responde o que o teste quer: o retry é o do SDK, no modo
 * standard, com 2 tentativas extras só para erros retentáveis.
 */
class DynamoDbConfigTest {

    private HttpServer servidor;
    private final AtomicInteger requisicoes = new AtomicInteger();
    private volatile int status;
    private volatile String tipoDoErro;
    private DynamoDbClient cliente;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/", troca -> {
            requisicoes.incrementAndGet();
            troca.getRequestBody().readAllBytes();
            var corpo = ("{\"__type\":\"com.amazonaws.dynamodb.v20120810#" + tipoDoErro + "\",\"message\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
            troca.sendResponseHeaders(status, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
        cliente = DynamoDbConfig.construirCliente(propriedades(
                "http://localhost:" + servidor.getAddress().getPort()), ObservationRegistry.NOOP);
    }

    @AfterEach
    void derrubarServidor() {
        cliente.close();
        servidor.stop(0);
    }

    @Test
    void erro5xxEhTentadoDeNovoDuasVezesPeloSdk() {
        status = 500;
        tipoDoErro = "InternalServerError";

        assertThatThrownBy(() -> cliente.getItem(b -> b.tableName("t").key(java.util.Map.of())))
                .isInstanceOf(DynamoDbException.class);
        assertThat(requisicoes).hasValue(3);
    }

    @Test
    void throttlingEhTentadoDeNovoDuasVezesPeloSdk() {
        status = 400;
        tipoDoErro = "ProvisionedThroughputExceededException";

        assertThatThrownBy(() -> cliente.getItem(b -> b.tableName("t").key(java.util.Map.of())))
                .isInstanceOf(DynamoDbException.class);
        assertThat(requisicoes).hasValue(3);
    }

    @Test
    void erro4xxNaoEhTentadoDeNovo() {
        status = 400;
        tipoDoErro = "ValidationException";

        assertThatThrownBy(() -> cliente.getItem(b -> b.tableName("t").key(java.util.Map.of())))
                .isInstanceOf(DynamoDbException.class);
        assertThat(requisicoes).hasValue(1);
    }

    @Test
    void estrategiaDeRetryTemUmaTentativaMaisAsExtrasConfiguradas() {
        var retry = new DynamoDbProperties.Retry(4, Duration.ofMillis(10), Duration.ofMillis(50));

        assertThat(DynamoDbConfig.retry(retry).maxAttempts()).isEqualTo(5);
    }

    @Test
    void configuracaoPadraoDoApplicationYml() throws IOException {
        var fontes = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var properties = new Binder(ConfigurationPropertySources.from(fontes),
                new PropertySourcesPlaceholdersResolver(fontes))
                .bind("consultas.dynamodb", DynamoDbProperties.class).get();

        assertThat(properties.retry().tentativasExtras()).isEqualTo(2);
        assertThat(properties.pool().maxConexoes()).isEqualTo(100);
        assertThat(properties.pool().timeoutObterConexao()).isEqualTo(Duration.ofMillis(20));
        assertThat(properties.timeoutTentativa()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.timeoutChamada()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void poolComMenosDeUmaConexaoEhRecusado() {
        assertThatThrownBy(() -> new DynamoDbProperties.Pool(0, Duration.ofMillis(100), Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DynamoDbProperties propriedades(String endpoint) {
        return new DynamoDbProperties("t", "sa-east-1", endpoint, new DynamoDbProperties.Credenciais("a", "b"),
                "gsi-cliente", "gsi-conta", Duration.ofSeconds(5), Duration.ofSeconds(2),
                new DynamoDbProperties.Pool(10, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new DynamoDbProperties.Retry(2, Duration.ofMillis(1), Duration.ofMillis(5)));
    }
}
