package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.TipoConsulta;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienciaDynamoDbTest {

    private static final Duration CIRCUITO_ABERTO = Duration.ofSeconds(7);
    private static final Duration PERIODO_RATE_LIMITER = Duration.ofSeconds(2);

    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("dynamodb", circuito());
    private final Bulkhead bulkhead = Bulkhead.of("dynamodb", BulkheadConfig.custom()
            .maxConcurrentCalls(3).maxWaitDuration(Duration.ZERO).build());
    private final Bulkhead bulkheadClienteConta = Bulkhead.of("dynamodb-cliente-conta", BulkheadConfig.custom()
            .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
    private final RateLimiter rateLimiter = RateLimiter.of("dynamodb", RateLimiterConfig.custom()
            .limitForPeriod(1000).limitRefreshPeriod(PERIODO_RATE_LIMITER).timeoutDuration(Duration.ZERO).build());
    private final ResilienciaDynamoDb resiliencia = new ResilienciaDynamoDb(
            circuitBreaker, bulkhead, bulkheadClienteConta, rateLimiter, Duration.ofSeconds(1));

    @AfterEach
    void semPrazo() {
        PrazoDaRequisicao.encerrar();
        Thread.interrupted();
    }

    @ParameterizedTest
    @EnumSource(TipoConsulta.class)
    void consultaLiberadaDevolveOResultado(TipoConsulta tipo) {
        assertThat(resiliencia.consulta(tipo, () -> "ok")).isEqualTo("ok");
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);
        assertThat(bulkheadClienteConta.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    }

    @Test
    void clienteEContaLotadosRecusamCom503MasOperacaoSegue() {
        assertThat(bulkheadClienteConta.tryAcquirePermission()).isTrue();

        for (var tipo : new TipoConsulta[]{TipoConsulta.CLIENTE, TipoConsulta.CONTA}) {
            assertThatThrownBy(() -> resiliencia.consulta(tipo, () -> "nunca"))
                    .isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                    .satisfies(e -> assertThat(((FonteDeOperacoesIndisponivelException) e).tenteNovamenteEm())
                            .isEqualTo(Duration.ofSeconds(1)));
        }
        assertThat(resiliencia.consulta(TipoConsulta.OPERACAO, () -> "ok")).isEqualTo("ok");
        // Recusada no bulkhead de CLIENTE/CONTA, a consulta não ocupou vaga no total
        assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(3);
    }

    @Test
    void bulkheadTotalLotadoRecusaOperacaoCom503() {
        for (int i = 0; i < 3; i++) {
            assertThat(bulkhead.tryAcquirePermission()).isTrue();
        }

        assertThatThrownBy(() -> resiliencia.consulta(TipoConsulta.OPERACAO, () -> "nunca"))
                .isInstanceOf(FonteDeOperacoesIndisponivelException.class);
    }

    @Test
    void rateLimiterSemPermissaoRecusaCom503EORetryAfterDoPeriodo() {
        rateLimiter.changeLimitForPeriod(1);
        rateLimiter.drainPermissions();

        assertThatThrownBy(() -> resiliencia.consulta(TipoConsulta.OPERACAO, () -> "nunca"))
                .isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                .satisfies(e -> assertThat(((FonteDeOperacoesIndisponivelException) e).tenteNovamenteEm())
                        .isEqualTo(PERIODO_RATE_LIMITER));
    }

    @Test
    void falhasDoSdkAbremOCircuitoEDepoisRecusamSemChamar() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                throw SdkClientException.create("conexão recusada");
            })).isExactlyInstanceOf(FalhaNaFonteDeOperacoesException.class);
        }
        var chamadas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", chamadas::incrementAndGet))
                .isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                .satisfies(e -> assertThat(((FonteDeOperacoesIndisponivelException) e).tenteNovamenteEm())
                        .isEqualTo(CIRCUITO_ABERTO));
        assertThat(chamadas).hasValue(0);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void timeoutsDoSdkViram504() {
        assertThatThrownBy(() -> resiliencia.chamada("Query", () -> {
            throw ApiCallTimeoutException.create(100);
        })).isInstanceOf(TempoEsgotadoNaFonteDeOperacoesException.class);
        assertThatThrownBy(() -> resiliencia.chamada("Query", () -> {
            throw ApiCallAttemptTimeoutException.create(100);
        })).isInstanceOf(TempoEsgotadoNaFonteDeOperacoesException.class);
    }

    @Test
    void chamadaInterrompidaNaoContaNoCircuito() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                throw AbortedException.create("interrompida");
            })).isInstanceOf(FalhaNaFonteDeOperacoesException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void prazoEsgotadoFalhaCom504SemChamarNemContarNoCircuito() throws InterruptedException {
        PrazoDaRequisicao.iniciar(Duration.ofMillis(1));
        Thread.sleep(5);
        var chamadas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", chamadas::incrementAndGet))
                .isInstanceOf(TempoEsgotadoNaFonteDeOperacoesException.class)
                .hasMessageContaining("prazo da requisição");
        assertThat(chamadas).hasValue(0);
        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void apiCallTimeoutDaRequisicaoEhOMenorEntreOTetoEOPrazoRestante() {
        assertThat(timeoutDaRequisicao()).isEqualTo(Duration.ofSeconds(1));

        PrazoDaRequisicao.iniciar(Duration.ofMillis(300));
        assertThat(timeoutDaRequisicao()).isLessThanOrEqualTo(Duration.ofMillis(300)).isPositive();

        PrazoDaRequisicao.iniciar(Duration.ofSeconds(30));
        assertThat(timeoutDaRequisicao()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void timeoutEncurtadoPeloPrazoDaRequisicaoViraTimeoutMasNaoContaNoCircuito() {
        PrazoDaRequisicao.iniciar(Duration.ofMillis(5));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                esperarOPrazoAcabar();
                throw ApiCallTimeoutException.create(5);
            })).isInstanceOf(TempoEsgotadoNaFonteDeOperacoesException.class);
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void timeoutComOTempoInteiroDaChamadaContaNoCircuito() {
        PrazoDaRequisicao.iniciar(Duration.ofSeconds(30));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("Query", () -> {
                throw ApiCallAttemptTimeoutException.create(100);
            })).isInstanceOf(TempoEsgotadoNaFonteDeOperacoesException.class);
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void erroDoClienteViraFalha502SemContarNoCircuito() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("Query", () -> {
                throw erroDoDynamoDb(400, "ValidationException");
            })).isExactlyInstanceOf(FalhaNaFonteDeOperacoesException.class);
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void throttlingDepoisDoRetryDoSdkVira503ComRetryAfterEContaNoCircuito() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                throw erroDoDynamoDb(400, "ProvisionedThroughputExceededException");
            })).isInstanceOf(VisaoConsolidadaSobrecarregadaException.class)
                    .satisfies(e -> assertThat(((FonteDeOperacoesIndisponivelException) e).tenteNovamenteEm())
                            .isPositive());
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void sobrecargaLancadaDentroDaChamadaContaNoCircuitoESaiComo503() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                throw new VisaoConsolidadaSobrecarregadaException("UnprocessedKeys restantes");
            })).isInstanceOf(VisaoConsolidadaSobrecarregadaException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void poolDeConexoesEsgotadoVira503SemContarNoCircuito() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("BatchGetItem", () -> {
                throw SdkClientException.builder()
                        .message("Unable to execute HTTP request: Timeout waiting for connection from pool")
                        .cause(new ConnectionRequestTimeoutException("Timeout deadline: 20 MILLISECONDS"))
                        .build();
            })).isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                    .satisfies(e -> assertThat(((FonteDeOperacoesIndisponivelException) e).tenteNovamenteEm())
                            .isPositive());
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void falha5xxContaNoCircuito() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> resiliencia.chamada("Query", () -> {
                throw erroDoDynamoDb(500, "InternalServerError");
            })).isExactlyInstanceOf(FalhaNaFonteDeOperacoesException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static CircuitBreakerConfig circuito() {
        var circuito = CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).waitDurationInOpenState(CIRCUITO_ABERTO);
        ResilienciaDynamoDb.classificarFalhas(circuito);
        return circuito.build();
    }

    private static AwsServiceException erroDoDynamoDb(int status, String codigo) {
        return DynamoDbException.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(codigo).errorMessage("x").build())
                .build();
    }

    private static void esperarOPrazoAcabar() {
        while (!PrazoDaRequisicao.esgotado()) {
            Thread.onSpinWait();
        }
    }

    private Duration timeoutDaRequisicao() {
        var configuracao = AwsRequestOverrideConfiguration.builder();
        resiliencia.noPrazo().accept(configuracao);
        return configuracao.build().apiCallTimeout().orElseThrow();
    }
}
