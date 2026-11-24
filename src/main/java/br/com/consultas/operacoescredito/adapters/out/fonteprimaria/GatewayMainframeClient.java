package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Gateway do mainframe (fonte primária): devolve cada bloco de uma operação e as listas de operações de um
 * cliente ou conta, tudo em trancode.
 */
@HttpExchange(url = "/mainframe", accept = MediaType.TEXT_PLAIN_VALUE)
interface GatewayMainframeClient {

    /**
     * @param bloco nome canônico do bloco
     * @throws org.springframework.web.client.HttpClientErrorException.NotFound quando a operação ou o
     *         bloco não existe na fonte primária
     */
    @GetExchange("/operacoes/{idOperacao}/blocos/{bloco}")
    String bloco(@PathVariable long idOperacao, @PathVariable String bloco);

    /**
     * Operações do cliente ({@code id_operacao} + data de contratação), mais recente primeiro.
     *
     * @throws org.springframework.web.client.HttpClientErrorException.NotFound quando o cliente não tem
     *         operações na fonte primária
     */
    @GetExchange("/clientes/{cpfCnpj}/operacoes")
    String operacoesDoCliente(@PathVariable String cpfCnpj);

    /**
     * Operações vinculadas à conta ({@code id_operacao} + data de contratação), mais recente primeiro.
     *
     * @throws org.springframework.web.client.HttpClientErrorException.NotFound quando a conta não tem
     *         operações na fonte primária
     */
    @GetExchange("/contas/{conta}/operacoes")
    String operacoesDaConta(@PathVariable String conta);
}
