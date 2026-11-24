package br.com.consultas.operacoescredito.adapters.out.eventos;

import br.com.consultas.operacoescredito.application.port.out.PublicadorDeEventosDeConsumo;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * Publicador do evento de consumo: SQS ou, com {@code consultas.eventos.habilitado=false}, desligado (sem cliente
 * SQS nem thread de envio).
 */
@Configuration(proxyBeanMethods = false)
class EventosSqsConfig {

    /** Instâncias do circuit breaker e do bulkhead do SQS em {@code resilience4j.*.instances}. */
    static final String RESILIENCIA = "sqs";

    private static final Logger log = LoggerFactory.getLogger(EventosSqsConfig.class);

    @Bean
    PublicadorDeEventosDeConsumo publicadorDeEventosDeConsumo(EventosSqsProperties properties,
                                                             CircuitBreakerRegistry circuitBreakers,
                                                             BulkheadRegistry bulkheads,
                                                             ObservationRegistry observationRegistry,
                                                             MeterRegistry meterRegistry) {
        if (!properties.habilitado()) {
            log.info("Publicação de eventos de consumo desligada (consultas.eventos.habilitado=false)");
            return evento -> {
                // desligado
            };
        }
        return new SqsPublicadorDeEventosDeConsumo(sqsClient(properties.sqs()), properties,
                circuitBreakers.circuitBreaker(RESILIENCIA), bulkheads.bulkhead(RESILIENCIA), observationRegistry,
                meterRegistry);
    }

    /** Cliente síncrono com timeout por tentativa e da chamada inteira; retry só o do SDK ({@code standard}). */
    private static SqsClient sqsClient(EventosSqsProperties.Sqs sqs) {
        var builder = SqsClient.builder()
                .region(Region.of(sqs.regiao()))
                .credentialsProvider(credenciais(sqs.credenciais()))
                .overrideConfiguration(c -> c
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallAttemptTimeout(sqs.timeoutTentativa())
                        .apiCallTimeout(sqs.timeoutChamada()));
        if (StringUtils.hasText(sqs.endpoint())) {
            builder.endpointOverride(URI.create(sqs.endpoint()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credenciais(EventosSqsProperties.Credenciais credenciais) {
        if (credenciais != null
                && StringUtils.hasText(credenciais.accessKeyId())
                && StringUtils.hasText(credenciais.secretAccessKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(credenciais.accessKeyId(), credenciais.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
