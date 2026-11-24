package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FalhasDoGatewayTest {

    @AfterEach
    void limparInterrupcaoEPrazo() {
        Thread.interrupted();
        PrazoDaRequisicao.encerrar();
    }

    @Test
    void erro5xxERetentavelEContaNoCircuito() {
        var erro = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "indisponivel", null, null, null);

        assertThat(FalhasDoGateway.retentavel(erro)).isTrue();
        assertThat(FalhasDoGateway.falhaDaDependencia(erro)).isTrue();
    }

    @Test
    void falhaOuTimeoutAoConectarERetentavel() {
        var recusada = new ResourceAccessException("recusada", new HttpHostConnectException("Connection refused"));
        var timeoutDeConexao = new ResourceAccessException("timeout", new ConnectTimeoutException("connect timed out"));

        assertThat(FalhasDoGateway.retentavel(recusada)).isTrue();
        assertThat(FalhasDoGateway.retentavel(timeoutDeConexao)).isTrue();
        assertThat(FalhasDoGateway.falhaDaDependencia(timeoutDeConexao)).isTrue();
    }

    @Test
    void timeoutDeRespostaNaoERetentavelMasContaNoCircuito() {
        var erro = new ResourceAccessException("lento", new SocketTimeoutException("Read timed out"));

        assertThat(FalhasDoGateway.retentavel(erro)).isFalse();
        assertThat(FalhasDoGateway.falhaDaDependencia(erro)).isTrue();
    }

    @Test
    void erro4xxNuncaERetentavelNemContaNoCircuito() {
        var erro = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalida", null, null, null);

        assertThat(FalhasDoGateway.retentavel(erro)).isFalse();
        assertThat(FalhasDoGateway.falhaDaDependencia(erro)).isFalse();
    }

    @Test
    void chamadaInterrompidaPeloFanOutNaoERetentadaEOCircuitoIgnora() {
        var erro = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "erro", null, null, null);
        Thread.currentThread().interrupt();

        assertThat(FalhasDoGateway.retentavel(erro)).isFalse();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(erro)).isTrue();
    }

    @Test
    void timeoutComOTempoConfiguradoInteiroContaNoCircuito() {
        PrazoDaRequisicao.iniciar(Duration.ofSeconds(5));
        var resposta = new ResourceAccessException("lento", new SocketTimeoutException("Read timed out"));
        var conexao = new ResourceAccessException("timeout", new ConnectTimeoutException("connect timed out"));

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(resposta)).isFalse();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(conexao)).isFalse();
    }

    @Test
    void timeoutSemPrazoDaRequisicaoContaNoCircuito() {
        var erro = new ResourceAccessException("lento", new SocketTimeoutException("Read timed out"));

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(erro)).isFalse();
    }

    @Test
    void timeoutEncurtadoPeloPrazoDaRequisicaoEIgnoradoPeloCircuito() {
        PrazoDaRequisicao.iniciar(Duration.ZERO);
        var resposta = new ResourceAccessException("lento", new SocketTimeoutException("Read timed out"));
        var conexao = new ResourceAccessException("timeout", new ConnectTimeoutException("connect timed out"));

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(resposta)).isTrue();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(conexao)).isTrue();
    }

    @Test
    void timeoutComMenosQueAFolgaDoPrazoEIgnoradoPeloCircuito() {
        PrazoDaRequisicao.iniciar(FalhasDoGateway.FOLGA_DO_PRAZO.dividedBy(2));
        var erro = new ResourceAccessException("lento", new SocketTimeoutException("Read timed out"));

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(erro)).isTrue();
    }

    @Test
    void erro5xxComOPrazoEsgotadoContinuaContandoNoCircuito() {
        PrazoDaRequisicao.iniciar(Duration.ZERO);
        var erro = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null);

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(erro)).isFalse();
        assertThat(FalhasDoGateway.falhaDaDependencia(erro)).isTrue();
    }

    @Test
    void recusaDoRateLimiterOuDoBulkheadEIgnoradaPeloCircuito() {
        var rateLimiter = RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("teste"));
        var bulkhead = BulkheadFullException.createBulkheadFullException(Bulkhead.ofDefaults("teste"));

        assertThat(FalhasDoGateway.ignoradaPeloCircuito(rateLimiter)).isTrue();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(bulkhead)).isTrue();
        assertThat(FalhasDoGateway.retentavel(rateLimiter)).isFalse();
        assertThat(FalhasDoGateway.retentavel(bulkhead)).isFalse();
    }

    @Test
    void prazoDaRequisicaoEsgotadoAntesDeChamarEIgnoradoPeloCircuito() {
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(new TempoEsgotadoNaFonteDeOperacoesException("prazo")))
                .isTrue();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null))).isFalse();
    }
}
