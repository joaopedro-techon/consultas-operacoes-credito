package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Gateway do mainframe (fonte primária): devolve cada bloco de uma operação em trancode. */
@HttpExchange(url = "/mainframe", accept = MediaType.TEXT_PLAIN_VALUE)
interface GatewayMainframeClient {

    /**
     * @param bloco nome canônico do bloco
     * @throws org.springframework.web.client.HttpClientErrorException.NotFound quando a operação ou o
     *         bloco não existe na fonte primária
     */
    @GetExchange("/operacoes/{idOperacao}/blocos/{bloco}")
    String bloco(@PathVariable long idOperacao, @PathVariable String bloco);
}
