package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.time.Duration;

/**
 * Classificação das falhas de uma chamada ao gateway do mainframe, usada pelo retry e pelo circuit breaker
 * (ADR-0009).
 * <ul>
 *     <li><b>Retentável</b>: 5xx, 429 do mainframe (com o backoff do retry), ou falha/timeout ao abrir a conexão (o pedido nem chegou ao mainframe).
 *     Nunca 4xx, nem timeout de resposta (o mainframe está lento: tentar de novo só piora) nem timeout do pool
 *     (a aplicação está saturada);</li>
 *     <li><b>Falha da dependência</b> (conta para abrir o circuito): 5xx e qualquer falha de I/O, inclusive
 *     timeouts que tiveram o tempo configurado inteiro. 4xx não é culpa do mainframe e conta como sucesso;</li>
 *     <li><b>Ignorada pelo circuito</b>: chamada interrompida porque outra chamada do mesmo fan-out falhou
 *     (senão uma falha viraria N); recusada antes de sair porque o prazo da requisição acabou; timeout que
 *     disparou com o prazo da requisição esgotado (o timeout foi encurtado pelo prazo, não esgotado pelo
 *     mainframe: num 504 de uma consulta CLIENTE grande, as chamadas em andamento não viram N falhas); e
 *     recusa do rate limiter ou do bulkhead da própria aplicação (sem chamada ao mainframe, não é sucesso
 *     nem falha dele).</li>
 * </ul>
 */
final class FalhasDoGateway {

    /**
     * Um timeout que dispara com menos que isto do prazo da requisição foi encurtado pelo prazo. A folga cobre o
     * arredondamento do timeout de socket para milissegundos e o agendamento da thread.
     */
    static final Duration FOLGA_DO_PRAZO = Duration.ofMillis(10);

    private FalhasDoGateway() {
    }

    static boolean retentavel(Throwable erro) {
        if (interrompida()) {
            return false;
        }
        return erro instanceof HttpServerErrorException
                || erro instanceof HttpClientErrorException.TooManyRequests
                || erro instanceof ResourceAccessException && falhaAoConectar(erro);
    }

    /** 5xx, I/O e 429 do mainframe (a cota dele estourou do lado dele: ADR-0014). */
    static boolean falhaDaDependencia(Throwable erro) {
        return erro instanceof HttpServerErrorException
                || erro instanceof HttpClientErrorException.TooManyRequests
                || erro instanceof ResourceAccessException;
    }

    static boolean ignoradaPeloCircuito(Throwable erro) {
        return interrompida()
                || erro instanceof TempoEsgotadoNaFonteDeOperacoesException
                || erro instanceof RequestNotPermitted
                || erro instanceof BulkheadFullException
                || timeout(erro) && !PrazoDaRequisicao.comporta(FOLGA_DO_PRAZO);
    }

    private static boolean falhaAoConectar(Throwable erro) {
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConnectException || causa instanceof ConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Timeouts de conexão, de obtenção de conexão do pool e de resposta são {@link InterruptedIOException}. */
    static boolean timeout(Throwable erro) {
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static boolean interrompida() {
        return Thread.currentThread().isInterrupted();
    }
}
