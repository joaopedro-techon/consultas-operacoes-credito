package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Gateway do mainframe (fonte primária): devolve cada bloco de uma operação e as listas de operações de um
 * cliente ou conta, tudo em trancode, sempre numa custódia ({@code SF}, {@code F5}, {@code OD}, {@code NSF}).
 * Blocos-lista e listas vêm em páginas de até 30 ocorrências (ADR-0016): sem {@code chave_continuacao}, a primeira;
 * com ela, a página seguinte à que devolveu a chave.
 * <p>
 * 404 ({@link org.springframework.web.client.HttpClientErrorException.NotFound}): a operação ou o bloco não existe
 * naquela custódia, ou o cliente/conta não tem operações nela.
 */
@HttpExchange(url = "/mainframe/custodias/{custodia}", accept = MediaType.TEXT_PLAIN_VALUE)
interface GatewayMainframeClient {

    String CHAVE_CONTINUACAO = "chave_continuacao";

    /** @param bloco nome canônico do bloco; {@code chaveContinuacao} {@code null} na primeira página */
    @GetExchange("/operacoes/{idOperacao}/blocos/{bloco}")
    String bloco(@PathVariable String custodia, @PathVariable long idOperacao, @PathVariable String bloco,
                 @RequestParam(name = CHAVE_CONTINUACAO, required = false) String chaveContinuacao);

    /** Operações do cliente na custódia ({@code id_operacao} + data de contratação), sem ordem garantida. */
    @GetExchange("/clientes/{cpfCnpj}/operacoes")
    String operacoesDoCliente(@PathVariable String custodia, @PathVariable String cpfCnpj,
                              @RequestParam(name = CHAVE_CONTINUACAO, required = false) String chaveContinuacao);

    /** Operações vinculadas à conta na custódia ({@code id_operacao} + data de contratação), sem ordem garantida. */
    @GetExchange("/contas/{conta}/operacoes")
    String operacoesDaConta(@PathVariable String custodia, @PathVariable String conta,
                            @RequestParam(name = CHAVE_CONTINUACAO, required = false) String chaveContinuacao);
}
