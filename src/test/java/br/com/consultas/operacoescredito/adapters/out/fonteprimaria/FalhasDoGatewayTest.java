package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FalhasDoGatewayTest {

    @AfterEach
    void limparInterrupcao() {
        Thread.interrupted();
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
    void prazoDaRequisicaoEsgotadoAntesDeChamarEIgnoradoPeloCircuito() {
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(new TempoEsgotadoNaFonteDeOperacoesException("prazo")))
                .isTrue();
        assertThat(FalhasDoGateway.ignoradaPeloCircuito(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "erro", null, null, null))).isFalse();
    }
}
