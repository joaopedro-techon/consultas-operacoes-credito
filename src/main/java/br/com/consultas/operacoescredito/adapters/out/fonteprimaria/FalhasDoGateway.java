package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;

/**
 * Classificação das falhas de uma chamada ao gateway do mainframe, usada pelo retry e pelo circuit breaker.
 * <ul>
 *     <li><b>Retentável</b>: 5xx, ou falha/timeout ao abrir a conexão (o pedido nem chegou ao mainframe).
 *     Nunca 4xx, nem timeout de resposta (o mainframe está lento: tentar de novo só piora) nem timeout do pool
 *     (a aplicação está saturada);</li>
 *     <li><b>Falha da dependência</b> (conta para abrir o circuito): 5xx e qualquer falha de I/O, inclusive
 *     timeouts. 4xx não é culpa do mainframe e conta como sucesso;</li>
 *     <li><b>Ignorada pelo circuito</b>: chamada interrompida porque outra chamada do mesmo fan-out falhou
 *     (senão uma falha viraria N), ou recusada antes de sair porque o prazo da requisição acabou.</li>
 * </ul>
 */
final class FalhasDoGateway {

    private FalhasDoGateway() {
    }

    static boolean retentavel(Throwable erro) {
        if (interrompida()) {
            return false;
        }
        return erro instanceof HttpServerErrorException
                || erro instanceof ResourceAccessException && falhaAoConectar(erro);
    }

    static boolean falhaDaDependencia(Throwable erro) {
        return erro instanceof HttpServerErrorException || erro instanceof ResourceAccessException;
    }

    static boolean ignoradaPeloCircuito(Throwable erro) {
        return interrompida() || erro instanceof TempoEsgotadoNaFonteDeOperacoesException;
    }

    private static boolean falhaAoConectar(Throwable erro) {
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConnectException || causa instanceof ConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean interrompida() {
        return Thread.currentThread().isInterrupted();
    }
}
