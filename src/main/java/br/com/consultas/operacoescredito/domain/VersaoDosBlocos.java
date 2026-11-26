package br.com.consultas.operacoescredito.domain;

/**
 * Regra de versão dos blocos (ADR-0019): se os blocos de uma operação numa custódia compartilham a versão do
 * registro.
 */
public enum VersaoDosBlocos {

    /**
     * Versão única: toda atualização regrava todos os blocos com a mesma versão, e uma consulta nunca entrega blocos
     * de versões diferentes da mesma operação (ADR-0018). Só vale com escritores que regravam a operação inteira.
     */
    UNICA,

    /**
     * Versão por bloco: uma atualização regrava só os blocos que mudaram, e cada bloco tem a sua versão. Uma consulta
     * pode entregar blocos de versões diferentes.
     */
    POR_BLOCO
}
