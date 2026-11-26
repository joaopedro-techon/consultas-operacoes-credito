package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private volatile Duration demora = Duration.ZERO;
    private DynamoDbClient cliente;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        servidor.createContext("/", troca -> {
            requisicoes.incrementAndGet();
            troca.getRequestBody().readAllBytes();
            try {
                Thread.sleep(demora);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var corpo = ("{\"__type\":\"com.amazonaws.dynamodb.v20120810#" + tipoDoErro + "\",\"message\":\"x\"}")
                    .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
            troca.sendResponseHeaders(status, corpo.length);
            troca.getResponseBody().write(corpo);
            troca.close();
        });
        servidor.start();
        cliente = DynamoDbConfig.construirCliente(propriedades(
                "http://localhost:" + servidor.getAddress().getPort()), ObservationRegistry.NOOP,
                new SimpleMeterRegistry());
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
    void poolEsgotadoEhReconhecidoComoSemConexaoNoPool() throws InterruptedException {
        status = 500;
        tipoDoErro = "InternalServerError";
        demora = Duration.ofMillis(800);
        var properties = new DynamoDbProperties("t", "sa-east-1", "http://localhost:" + servidor.getAddress().getPort(),
                new DynamoDbProperties.Credenciais("a", "b"), "gsi-cliente", "gsi-conta", Duration.ofSeconds(5),
                Duration.ofSeconds(2), 4,
                new DynamoDbProperties.Pool(1, Duration.ofSeconds(1), Duration.ofMillis(30), Duration.ofSeconds(2),
                        Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new DynamoDbProperties.Retry(0, Duration.ofMillis(1), Duration.ofMillis(5)));
        try (var comUmaConexao = DynamoDbConfig.construirCliente(properties, ObservationRegistry.NOOP,
                new SimpleMeterRegistry())) {
            var ocupando = Thread.ofVirtual().start(() -> {
                try {
                    comUmaConexao.getItem(b -> b.tableName("t").key(java.util.Map.of()));
                } catch (RuntimeException e) {
                    // a resposta 500 não interessa: a chamada só ocupa a única conexão
                }
            });
            while (requisicoes.get() == 0) {
                Thread.sleep(5);
            }

            assertThatThrownBy(() -> comUmaConexao.getItem(b -> b.tableName("t").key(java.util.Map.of())))
                    .satisfies(e -> assertThat(ResilienciaDynamoDb.semConexaoNoPool(e)).as(e.toString()).isTrue());
            ocupando.join();
        }
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
        assertThat(properties.pool().maxConexoes()).isEqualTo(300);
        assertThat(properties.pool().timeoutObterConexao()).isEqualTo(Duration.ofMillis(20));
        assertThat(properties.timeoutTentativa()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.timeoutChamada()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.maxLotesSimultaneos()).isEqualTo(4);
    }

    @Test
    void poolPadraoComportaOPiorCasoDosBulkheadsPadrao() throws IOException {
        var fontes = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var binder = new Binder(ConfigurationPropertySources.from(fontes), new PropertySourcesPlaceholdersResolver(fontes));
        var properties = binder.bind("consultas.dynamodb", DynamoDbProperties.class).get();
        int total = binder.bind("resilience4j.bulkhead.instances.dynamodb.max-concurrent-calls", Integer.class).get();
        int clienteConta = binder.bind("resilience4j.bulkhead.instances.dynamodb-cliente-conta.max-concurrent-calls",
                Integer.class).get();

        assertThat(DynamoDbConfig.conexoesNecessarias(total, clienteConta, properties.maxLotesSimultaneos()))
                .isLessThanOrEqualTo(properties.pool().maxConexoes());
        DynamoDbConfig.validarDimensionamento(properties.pool().maxConexoes(), total, clienteConta,
                properties.maxLotesSimultaneos());
    }

    @Test
    void conexoesNecessariasSomamOperacaoEOsLotesDeClienteEConta() {
        // 140 OPERACAO com uma conexão cada + 40 CLIENTE/CONTA com até 4 lotes cada
        assertThat(DynamoDbConfig.conexoesNecessarias(180, 40, 4)).isEqualTo(300);
        // Bulkhead de CLIENTE/CONTA maior que o total: vale o total
        assertThat(DynamoDbConfig.conexoesNecessarias(10, 50, 2)).isEqualTo(20);
    }

    @Test
    void poolMenorQueOPiorCasoDosBulkheadsFalhaNaSubida() {
        assertThatThrownBy(() -> DynamoDbConfig.validarDimensionamento(100, 200, 60, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-conexoes (100)")
                .hasMessageContaining("620");
    }

    @Test
    void lotesSimultaneosPrecisamSerAoMenosUm() {
        assertThatThrownBy(() -> new DynamoDbProperties("t", "sa-east-1", null, null, "gsi-cliente", "gsi-conta",
                Duration.ofSeconds(1), Duration.ofMillis(100), 0, pool(10), retry()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void poolComMenosDeUmaConexaoEhRecusado() {
        assertThatThrownBy(() -> new DynamoDbProperties.Pool(0, Duration.ofMillis(100), Duration.ofMillis(20),
                Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DynamoDbProperties propriedades(String endpoint) {
        return new DynamoDbProperties("t", "sa-east-1", endpoint, new DynamoDbProperties.Credenciais("a", "b"),
                "gsi-cliente", "gsi-conta", Duration.ofSeconds(5), Duration.ofSeconds(2), 4, pool(10), retry());
    }

    private static DynamoDbProperties.Pool pool(int maxConexoes) {
        return new DynamoDbProperties.Pool(maxConexoes, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    private static DynamoDbProperties.Retry retry() {
        return new DynamoDbProperties.Retry(2, Duration.ofMillis(1), Duration.ofMillis(5));
    }
}
