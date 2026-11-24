package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.CotaDaFonteEsgotadaException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.domain.Custodia;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proteção por custódia × rota (ADR-0014) e cota local (ADR-0015), com registros em memória. */
class ResilienciaFontePrimariaTest {

    private static final String PARCELAS = "parcelas";
    private static final String SALDO = "saldo";

    private final CircuitBreakerRegistry circuitos = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();

    ResilienciaFontePrimariaTest() {
        circuitos.circuitBreaker(ResilienciaFontePrimaria.NOME, CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(7))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        retries.retry(ResilienciaFontePrimaria.NOME, RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(1))
                .build());
        bulkheads.bulkhead(ResilienciaFontePrimaria.NOME, BulkheadConfig.custom()
                .maxConcurrentCalls(2)
                .maxWaitDuration(Duration.ZERO)
                .build());
        bulkheads.bulkhead(ResilienciaFontePrimaria.BULKHEAD_CLIENTE_CONTA, BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    @Test
    void retentaFalha5xxAteDarCerto() {
        var tentativas = new AtomicInteger();
        var resiliencia = resiliencia(1000);

        var resultado = resiliencia.executar(Custodia.SF, PARCELAS, false, () -> {
            if (tentativas.incrementAndGet() < 3) {
                throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null);
            }
            return "ok";
        });

        assertThat(resultado).isEqualTo("ok");
        assertThat(tentativas).hasValue(3);
    }

    @Test
    void naoRetentaErro4xx() {
        var tentativas = new AtomicInteger();
        var resiliencia = resiliencia(1000);

        assertThatThrownBy(() -> resiliencia.executar(Custodia.SF, PARCELAS, false, () -> {
            tentativas.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalida", null, null, null);
        })).isInstanceOf(HttpClientErrorException.class);
        assertThat(tentativas).hasValue(1);
    }

    @Test
    void cadaCustodiaEBlocoTemOSeuCircuito() {
        var resiliencia = resiliencia(1000);
        resiliencia.executar(Custodia.F5, PARCELAS, false, () -> "cria");
        circuitos.circuitBreaker(ResilienciaFontePrimaria.nome(Custodia.F5, PARCELAS)).transitionToOpenState();
        var tentativas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.executar(Custodia.F5, PARCELAS, false, tentativas::incrementAndGet))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class, e -> {
                    assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(e).hasMessageContaining("fontePrimaria-F5-parcelas");
                });
        assertThat(tentativas).as("circuito aberto não chama nem retenta").hasValue(0);
        assertThat(resiliencia.executar(Custodia.F5, SALDO, false, () -> "outro bloco")).isEqualTo("outro bloco");
        assertThat(resiliencia.executar(Custodia.SF, PARCELAS, false, () -> "outra custódia"))
                .isEqualTo("outra custódia");
    }

    @Test
    void instanciaDeclaradaComONomeDaRotaSobrescreveABaseEMantemAClassificacao() {
        retries.retry(ResilienciaFontePrimaria.nome(Custodia.OD, PARCELAS),
                RetryConfig.custom().maxAttempts(1).build());
        var resiliencia = resiliencia(1000);
        var tentativas = new AtomicInteger();

        assertThatThrownBy(() -> resiliencia.executar(Custodia.OD, PARCELAS, false, () -> {
            tentativas.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null);
        })).isInstanceOf(HttpServerErrorException.class);

        assertThat(tentativas).as("maxAttempts da instância declarada").hasValue(1);
        assertThat(circuitos.circuitBreaker(ResilienciaFontePrimaria.nome(Custodia.OD, PARCELAS)).getMetrics()
                .getNumberOfFailedCalls()).as("5xx conta no circuito da rota").isEqualTo(1);
    }

    @Test
    void cotaEsgotadaVira429ComOPeriodoDoLimiteENaoContaNoCircuito() {
        var limitador = RateLimiter.of(ResilienciaFontePrimaria.NOME, RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(30))
                .timeoutDuration(Duration.ZERO)
                .build());
        var resiliencia = new ResilienciaFontePrimaria(circuitos, retries, limitador, bulkheads, Duration.ZERO, 1);
        resiliencia.executar(Custodia.SF, PARCELAS, false, () -> "primeira");

        assertThatThrownBy(() -> resiliencia.executar(Custodia.NSF, SALDO, false, () -> "segunda"))
                .isInstanceOfSatisfying(CotaDaFonteEsgotadaException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(30)));
        var circuito = circuitos.circuitBreaker(ResilienciaFontePrimaria.nome(Custodia.NSF, SALDO));
        assertThat(circuito.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void cotaDaInstanciaSubstituiOLimiteDoApplicationYml() {
        var limitador = RateLimiter.of(ResilienciaFontePrimaria.NOME, RateLimiterConfig.custom()
                .limitForPeriod(2000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .build());

        new ResilienciaFontePrimaria(circuitos, retries, limitador, bulkheads, Duration.ZERO, 500);

        assertThat(limitador.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(500);
    }

    @Test
    void cotaPorSegundoViraPermissoesDoPeriodoDoRateLimiter() {
        assertThat(ResilienciaFontePrimaria.limitePorPeriodo(500, Duration.ofMillis(100))).isEqualTo(50);
        assertThat(ResilienciaFontePrimaria.limitePorPeriodo(500, Duration.ofSeconds(1))).isEqualTo(500);
        assertThat(ResilienciaFontePrimaria.limitePorPeriodo(5, Duration.ofMillis(100))).as("ao menos 1").isEqualTo(1);
    }

    @Test
    void bulkheadCheioDeUmaCustodiaNaoAfetaAsOutras() {
        var resiliencia = resiliencia(1000);
        ocupar(ResilienciaFontePrimaria.NOME + "-F5");

        assertThatThrownBy(() -> resiliencia.executar(Custodia.F5, PARCELAS, false, () -> "sem vaga"))
                .isInstanceOfSatisfying(FonteDeOperacoesIndisponivelException.class,
                        e -> assertThat(e.tenteNovamenteEm()).isEqualTo(Duration.ofSeconds(1)));
        var circuito = circuitos.circuitBreaker(ResilienciaFontePrimaria.nome(Custodia.F5, PARCELAS));
        assertThat(circuito.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(circuito.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(resiliencia.executar(Custodia.SF, PARCELAS, false, () -> "SF")).isEqualTo("SF");
    }

    @Test
    void bulkheadDeClienteContaCheioRecusaClienteContaMasNaoOperacao() {
        var resiliencia = resiliencia(1000);
        ocupar(ResilienciaFontePrimaria.BULKHEAD_CLIENTE_CONTA);

        assertThatThrownBy(() -> resiliencia.executar(Custodia.SF, "lista_cliente", true, () -> "cliente"))
                .isInstanceOf(FonteDeOperacoesIndisponivelException.class)
                .hasMessageContaining(ResilienciaFontePrimaria.BULKHEAD_CLIENTE_CONTA);
        assertThat(resiliencia.executar(Custodia.SF, PARCELAS, false, () -> "operacao")).isEqualTo("operacao");
    }

    @Test
    void chamadasDeClienteContaOcupamOBulkheadDelasEODaCustodia() {
        var resiliencia = resiliencia(1000);
        var clienteConta = bulkheads.bulkhead(ResilienciaFontePrimaria.BULKHEAD_CLIENTE_CONTA);
        var daCustodia = bulkheads.bulkhead(ResilienciaFontePrimaria.NOME + "-SF");

        var vagas = resiliencia.executar(Custodia.SF, PARCELAS, true, () -> new int[] {
                clienteConta.getMetrics().getAvailableConcurrentCalls(),
                daCustodia.getMetrics().getAvailableConcurrentCalls()});

        assertThat(vagas).containsExactly(0, 1);
        assertThat(clienteConta.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
        assertThat(daCustodia.getMetrics().getAvailableConcurrentCalls()).isEqualTo(2);
    }

    private void ocupar(String bulkhead) {
        var alvo = bulkheads.bulkhead(bulkhead);
        while (alvo.tryAcquirePermission()) {
            // ocupa todas as vagas
        }
    }

    private ResilienciaFontePrimaria resiliencia(int chamadasPorSegundo) {
        var limitador = RateLimiter.of(ResilienciaFontePrimaria.NOME, RateLimiterConfig.custom()
                .limitForPeriod(chamadasPorSegundo)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        return new ResilienciaFontePrimaria(circuitos, retries, limitador, bulkheads, Duration.ZERO,
                chamadasPorSegundo);
    }
}
