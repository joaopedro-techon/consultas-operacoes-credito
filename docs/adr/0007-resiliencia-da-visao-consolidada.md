# Resiliência da visão consolidada: proteção por consulta, circuito por chamada, retry só do SDK

O DynamoDB tem proteção própria do Resilience4j, isolada da fonte primária. Bulkheads e rate limiter valem **por consulta**, antes de qualquer I/O: uma consulta CLIENTE com dois lotes paralelos ocupa uma vaga, não duas, e a recusa (503 + `Retry-After`) sai antes de gastar Valkey ou DynamoDB. CLIENTE e CONTA passam primeiro por um bulkhead próprio (`dynamodb-cliente-conta`), menor que o total (`dynamodb`), então sempre sobra vaga para OPERACAO. O circuit breaker vale **por chamada ao SDK** (`Query`, `BatchGetItem`), porque é ali que está o resultado da dependência; chamadas interrompidas pela própria aplicação (lote cancelado) não contam.

Há uma camada de retry só, a do SDK (modo `standard`, 2 tentativas extras, backoff exponencial com jitter de 10 a 50 ms), e nenhuma do Resilience4j no DynamoDB. O prazo da requisição (ADR-0006) chega ao SDK como `apiCallTimeout` de cada requisição, o menor entre o teto da chamada e o que resta do prazo; assim o SDK não começa uma tentativa ou um backoff que passe do prazo. A releitura das `UnprocessedKeys` no adapter continua (até 3 leituras com backoff), porque não é retry de erro: é a continuação de uma resposta parcial bem-sucedida que o SDK não trata; ela também só acontece se o prazo comportar.

## Considered Options

Envolver o caso de uso num timeout (thread separada, cancelada no prazo) daria o 504 mesmo com uma fonte que ignora o prazo, mas deixaria chamadas e retries correndo em segundo plano e duplicaria o mecanismo do ADR-0006. Rejeitado.
