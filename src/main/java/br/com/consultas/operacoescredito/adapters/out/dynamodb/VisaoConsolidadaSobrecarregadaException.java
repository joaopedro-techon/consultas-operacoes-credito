package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;

import java.time.Duration;

/**
 * O DynamoDB limitou a leitura (throttling que sobrou depois do retry do SDK, ou {@code UnprocessedKeys} que
 * sobraram depois das releituras): 503 com {@code Retry-After}, porque é capacidade momentânea, não erro. Conta
 * como falha no circuit breaker {@value ResilienciaDynamoDb#NOME}: throttling persistente abre o circuito.
 */
class VisaoConsolidadaSobrecarregadaException extends FonteDeOperacoesIndisponivelException {

    /** Sugestão de espera: o backoff do throttling do DynamoDB é da ordem de centenas de milissegundos. */
    static final Duration TENTE_NOVAMENTE_EM = Duration.ofSeconds(1);

    VisaoConsolidadaSobrecarregadaException(String mensagem) {
        super(mensagem, TENTE_NOVAMENTE_EM, null);
    }

    VisaoConsolidadaSobrecarregadaException(String mensagem, Throwable causa) {
        super(mensagem, TENTE_NOVAMENTE_EM, causa);
    }
}
