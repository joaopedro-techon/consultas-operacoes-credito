package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Cliente do DynamoDB e a proteção da visão consolidada ({@link ResilienciaDynamoDb}). Uma camada de retry só:
 * a do SDK (modo {@code standard}); o Resilience4j não faz retry no DynamoDB.
 */
@Configuration(proxyBeanMethods = false)
class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(DynamoDbProperties properties, ObservationRegistry observationRegistry) {
        return construirCliente(properties, observationRegistry);
    }

    @Bean
    ResilienciaDynamoDb resilienciaDynamoDb(DynamoDbProperties properties, CircuitBreakerRegistry circuitBreakers,
                                            BulkheadRegistry bulkheads, RateLimiterRegistry rateLimiters) {
        var bulkhead = bulkheads.bulkhead(ResilienciaDynamoDb.NOME);
        var bulkheadClienteConta = bulkheads.bulkhead(ResilienciaDynamoDb.BULKHEAD_CLIENTE_CONTA);
        validarDimensionamento(properties.pool().maxConexoes(),
                bulkhead.getBulkheadConfig().getMaxConcurrentCalls(),
                bulkheadClienteConta.getBulkheadConfig().getMaxConcurrentCalls(),
                properties.maxLotesSimultaneos());
        return new ResilienciaDynamoDb(
                circuitBreakers.circuitBreaker(ResilienciaDynamoDb.NOME),
                bulkhead,
                bulkheadClienteConta,
                rateLimiters.rateLimiter(ResilienciaDynamoDb.NOME),
                properties.timeoutChamada());
    }

    /**
     * Conexões que as consultas admitidas pelos bulkheads podem ocupar ao mesmo tempo: cada OPERACAO uma (uma
     * chamada por vez), cada CLIENTE/CONTA até {@code max-lotes-simultaneos} (a {@code Query} vem antes dos lotes).
     * {@code (bulkhead dynamodb − bulkhead dynamodb-cliente-conta) + bulkhead dynamodb-cliente-conta ×
     * max-lotes-simultaneos}.
     */
    static int conexoesNecessarias(int bulkheadTotal, int bulkheadClienteConta, int maxLotesSimultaneos) {
        var clienteConta = Math.min(bulkheadTotal, bulkheadClienteConta);
        return bulkheadTotal - clienteConta + clienteConta * maxLotesSimultaneos;
    }

    /**
     * Falha na subida se o pool não comporta o pior caso dos bulkheads: senão, com o DynamoDB saudável, consultas
     * admitidas esperariam conexão e falhariam por {@code timeout-obter-conexao} (503).
     */
    static void validarDimensionamento(int maxConexoes, int bulkheadTotal, int bulkheadClienteConta,
                                       int maxLotesSimultaneos) {
        var necessarias = conexoesNecessarias(bulkheadTotal, bulkheadClienteConta, maxLotesSimultaneos);
        if (maxConexoes < necessarias) {
            throw new IllegalStateException("consultas.dynamodb.pool.max-conexoes (" + maxConexoes
                    + ") menor que as conexões que os bulkheads admitem (" + necessarias + " = (dynamodb "
                    + bulkheadTotal + " − dynamodb-cliente-conta " + bulkheadClienteConta
                    + ") + dynamodb-cliente-conta × max-lotes-simultaneos " + maxLotesSimultaneos + ")");
        }
    }

    /**
     * Não contam no circuito: chamadas cortadas pela própria aplicação, timeouts encurtados pelo prazo da
     * requisição, pool de conexões esgotado e 4xx que não são throttling ({@link ResilienciaDynamoDb#ignoradaPeloCircuito}).
     */
    @Bean
    CircuitBreakerConfigCustomizer circuitBreakerDoDynamoDb() {
        return CircuitBreakerConfigCustomizer.of(ResilienciaDynamoDb.NOME, ResilienciaDynamoDb::classificarFalhas);
    }

    static DynamoDbClient construirCliente(DynamoDbProperties properties, ObservationRegistry observationRegistry) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(properties.regiao()))
                .httpClient(clienteHttp(properties.pool()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .addExecutionInterceptor(new ObservacaoDynamoDbInterceptor(observationRegistry))
                        .retryStrategy(retry(properties.retry()))
                        .apiCallTimeout(properties.timeoutChamada())
                        .apiCallAttemptTimeout(properties.timeoutTentativa())
                        .build())
                .credentialsProvider(credenciais(properties.credenciais()));
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    /** Pool de conexões do SDK (Apache HttpClient 5, o cliente síncrono padrão do SDK). */
    static SdkHttpClient clienteHttp(DynamoDbProperties.Pool pool) {
        return Apache5HttpClient.builder()
                .maxConnections(pool.maxConexoes())
                .connectionTimeout(pool.timeoutConexao())
                .connectionAcquisitionTimeout(pool.timeoutObterConexao())
                .socketTimeout(pool.timeoutResposta())
                .connectionTimeToLive(pool.ttl())
                .connectionMaxIdleTime(pool.maxOciosa())
                .tcpKeepAlive(true)
                .build();
    }

    /**
     * Modo {@code standard} com as condições de retry da AWS (throttling, 5xx, falha de conexão), {@code 1 +
     * tentativas-extras} tentativas e backoff exponencial com jitter, também no throttling.
     */
    static RetryStrategy retry(DynamoDbProperties.Retry retry) {
        var backoff = BackoffStrategy.exponentialDelay(retry.backoffBase(), retry.backoffMaximo());
        return AwsRetryStrategy.standardRetryStrategy().toBuilder()
                .maxAttempts(1 + retry.tentativasExtras())
                .backoffStrategy(backoff)
                .throttlingBackoffStrategy(backoff)
                .build();
    }

    private static AwsCredentialsProvider credenciais(DynamoDbProperties.Credenciais credenciais) {
        if (credenciais != null
                && StringUtils.hasText(credenciais.accessKeyId())
                && StringUtils.hasText(credenciais.secretAccessKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(credenciais.accessKeyId(), credenciais.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
