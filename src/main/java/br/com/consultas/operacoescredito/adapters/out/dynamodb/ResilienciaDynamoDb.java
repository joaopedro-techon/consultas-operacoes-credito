package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.TipoConsulta;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;

import java.nio.channels.ClosedByInterruptException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Proteção própria da visão consolidada, isolada da fonte primária. Nomes das instâncias do Resilience4j (valores
 * em {@code resilience4j.*.instances} no {@code application.yml}):
 * <ul>
 *     <li>por consulta, antes de qualquer I/O: bulkhead {@value #BULKHEAD_CLIENTE_CONTA} (só CLIENTE/CONTA, teto
 *     próprio abaixo do total para não sufocar OPERACAO) → bulkhead {@value #NOME} (todas) → rate limiter
 *     {@value #NOME};</li>
 *     <li>por chamada ao SDK ({@code Query}, {@code BatchGetItem}): circuit breaker {@value #NOME}, que vê o
 *     resultado depois do retry do SDK (a única camada de retry do DynamoDB, sem retry do Resilience4j).</li>
 * </ul>
 * Recusas da proteção e pool de conexões esgotado viram {@link FonteDeOperacoesIndisponivelException} (503 com
 * {@code Retry-After}); throttling do DynamoDB depois do retry do SDK, {@link VisaoConsolidadaSobrecarregadaException}
 * (503); timeouts e prazo da requisição esgotado, {@link TempoEsgotadoNaFonteDeOperacoesException} (504); demais
 * falhas do SDK, {@link FalhaNaFonteDeOperacoesException} (502). O que conta no circuito está em
 * {@link #ignoradaPeloCircuito}.
 */
final class ResilienciaDynamoDb {

    /** Circuit breaker, bulkhead e rate limiter do DynamoDB (e valor da tag {@code name} nas métricas). */
    static final String NOME = "dynamodb";
    /** Bulkhead das consultas CLIENTE e CONTA. */
    static final String BULKHEAD_CLIENTE_CONTA = "dynamodb-cliente-conta";

    /**
     * Um timeout que dispara com menos que isto do prazo da requisição foi encurtado pelo prazo (o mesmo critério
     * da fonte primária, ADR-0009).
     */
    static final Duration FOLGA_DO_PRAZO = Duration.ofMillis(10);

    private static final Duration TENTE_NOVAMENTE_SOBRECARGA = Duration.ofSeconds(1);
    private static final String FONTE = "visão consolidada (DynamoDB)";

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Bulkhead bulkheadClienteConta;
    private final RateLimiter rateLimiter;
    private final Duration timeoutChamada;

    ResilienciaDynamoDb(CircuitBreaker circuitBreaker, Bulkhead bulkhead, Bulkhead bulkheadClienteConta,
                        RateLimiter rateLimiter, Duration timeoutChamada) {
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead");
        this.bulkheadClienteConta = Objects.requireNonNull(bulkheadClienteConta, "bulkheadClienteConta");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.timeoutChamada = Objects.requireNonNull(timeoutChamada, "timeoutChamada");
    }

    /** Executa a consulta inteira sob os bulkheads do tipo de consulta e o rate limiter. */
    <T> T consulta(TipoConsulta tipo, Supplier<T> consulta) {
        Supplier<T> protegida = Bulkhead.decorateSupplier(bulkhead, RateLimiter.decorateSupplier(rateLimiter, consulta));
        if (tipo != TipoConsulta.OPERACAO) {
            protegida = Bulkhead.decorateSupplier(bulkheadClienteConta, protegida);
        }
        try {
            return protegida.get();
        } catch (BulkheadFullException e) {
            throw new FonteDeOperacoesIndisponivelException(
                    "Bulkhead da " + FONTE + " cheio para " + tipo, TENTE_NOVAMENTE_SOBRECARGA, e);
        } catch (RequestNotPermitted e) {
            throw new FonteDeOperacoesIndisponivelException("Rate limiter da " + FONTE + " sem permissões",
                    rateLimiter.getRateLimiterConfig().getLimitRefreshPeriod(), e);
        }
    }

    /**
     * Uma chamada ao SDK sob o circuit breaker, só se ainda houver prazo. A requisição da chamada deve usar
     * {@link #noPrazo()} para que o SDK (tentativas e backoff incluídos) não passe do que resta do prazo.
     */
    <T> T chamada(String operacao, Supplier<T> chamada) {
        if (PrazoDaRequisicao.esgotado()) {
            throw prazoEsgotado(operacao, null);
        }
        try {
            return circuitBreaker.executeSupplier(chamada);
        } catch (CallNotPermittedException e) {
            throw new FonteDeOperacoesIndisponivelException(
                    "Circuit breaker da " + FONTE + " aberto", esperaComCircuitoAberto(), e);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            throw prazoEsgotado(operacao, e);
        } catch (AbortedException e) {
            throw interrompida(operacao, e);
        } catch (SdkException e) {
            if (interrompida(e)) {
                throw interrompida(operacao, e);
            }
            if (semConexaoNoPool(e)) {
                throw new FonteDeOperacoesIndisponivelException("Pool de conexões da " + FONTE + " esgotado no "
                        + operacao, TENTE_NOVAMENTE_SOBRECARGA, e);
            }
            if (e instanceof AwsServiceException servico && servico.isThrottlingException()) {
                throw new VisaoConsolidadaSobrecarregadaException(
                        "Throttling no " + operacao + " da " + FONTE + " depois do retry do SDK", e);
            }
            throw new FalhaNaFonteDeOperacoesException("Falha no " + operacao + " da " + FONTE, e);
        }
    }

    /** {@code apiCallTimeout} da requisição: o teto de uma chamada limitado ao que resta do prazo da requisição. */
    Consumer<AwsRequestOverrideConfiguration.Builder> noPrazo() {
        var timeout = PrazoDaRequisicao.limitar(timeoutChamada);
        return configuracao -> configuracao.apiCallTimeout(timeout);
    }

    /**
     * O que o circuit breaker {@value #NOME} não conta (nem como sucesso nem como falha), no customizer e nos
     * testes. Mesmo raciocínio do circuito da fonte primária (ADR-0009): só conta o que é culpa do DynamoDB.
     */
    static void classificarFalhas(CircuitBreakerConfig.Builder circuito) {
        circuito.ignoreException(ResilienciaDynamoDb::ignoradaPeloCircuito);
    }

    /**
     * {@code true} para falhas que não dizem nada sobre a saúde do DynamoDB:
     * <ul>
     *     <li>chamada interrompida pela própria aplicação (lote cancelado, requisição encerrada);</li>
     *     <li>timeout que disparou com menos de {@link #FOLGA_DO_PRAZO} de prazo restante: o {@code apiCallTimeout}
     *     foi encurtado pelo prazo da requisição ({@link #noPrazo()}), não esgotado pelo DynamoDB;</li>
     *     <li>sem conexão livre no pool do cliente HTTP dentro de {@code timeout-obter-conexao}: saturação da
     *     aplicação, não do DynamoDB;</li>
     *     <li>erro do cliente (4xx que não é throttling, ex.: {@code ValidationException}): a requisição é que
     *     estava errada. Throttling (400 com código de throttling) conta: é o DynamoDB sem capacidade.</li>
     * </ul>
     */
    static boolean ignoradaPeloCircuito(Throwable erro) {
        return interrompida(erro)
                || timeout(erro) && !PrazoDaRequisicao.comporta(FOLGA_DO_PRAZO)
                || semConexaoNoPool(erro)
                || erroDoCliente(erro);
    }

    private static boolean timeout(Throwable erro) {
        return erro instanceof ApiCallTimeoutException || erro instanceof ApiCallAttemptTimeoutException;
    }

    /** O Apache HttpClient não conseguiu conexão do pool a tempo ({@code connectionAcquisitionTimeout}). */
    static boolean semConexaoNoPool(Throwable erro) {
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConnectionRequestTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean erroDoCliente(Throwable erro) {
        return erro instanceof AwsServiceException servico
                && servico.statusCode() >= 400 && servico.statusCode() < 500
                && !servico.isThrottlingException();
    }

    /** Chamada cortada pelo cancelamento de outro lote ou pelo fim da requisição: não é falha do DynamoDB. */
    static boolean interrompida(Throwable erro) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof AbortedException || causa instanceof InterruptedException
                    || causa instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    private static TempoEsgotadoNaFonteDeOperacoesException prazoEsgotado(String operacao, Throwable causa) {
        var motivo = PrazoDaRequisicao.esgotado() ? "prazo da requisição esgotado" : "tempo da chamada esgotado";
        return new TempoEsgotadoNaFonteDeOperacoesException(operacao + " na " + FONTE + ": " + motivo, causa);
    }

    private static FalhaNaFonteDeOperacoesException interrompida(String operacao, Throwable causa) {
        return PrazoDaRequisicao.esgotado()
                ? prazoEsgotado(operacao, causa)
                : new FalhaNaFonteDeOperacoesException(operacao + " na " + FONTE + " interrompido", causa);
    }

    private Duration esperaComCircuitoAberto() {
        return Duration.ofMillis(circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
    }
}
