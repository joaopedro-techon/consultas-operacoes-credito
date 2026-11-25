package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;

import java.time.Duration;

/**
 * Os blocos de uma operação continuaram em versões do registro diferentes mesmo depois das releituras consistentes
 * (ou não sobrou prazo para relê-los): 503 com {@code Retry-After} (ADR-0018). Uma consulta nunca entrega blocos de
 * versões diferentes da mesma operação, e omitir a operação de uma lista seria uma resposta errada e silenciosa. É
 * uma atualização em andamento, não falha do DynamoDB: não conta no circuit breaker.
 */
class VersaoDivergenteException extends FonteDeOperacoesIndisponivelException {

    /** Uma atualização (transação) leva da ordem de centenas de milissegundos para ficar visível em todo lugar. */
    static final Duration TENTE_NOVAMENTE_EM = Duration.ofSeconds(1);

    VersaoDivergenteException(String mensagem) {
        super(mensagem, TENTE_NOVAMENTE_EM, null);
    }
}
