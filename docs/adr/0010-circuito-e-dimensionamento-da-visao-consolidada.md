# Circuito da visão consolidada conta só o que é culpa do DynamoDB; pool dimensionado pelos bulkheads

O circuit breaker `dynamodb` vê cada `Query`/`BatchGetItem` depois do retry do SDK, mas várias falhas que chegam até ele são da própria aplicação. Seguindo o mesmo raciocínio do circuito da fonte primária (ADR-0009, hoje só no repositório do mainframe), `ResilienciaDynamoDb.ignoradaPeloCircuito` **ignora** (nem sucesso nem falha): chamadas interrompidas (lote cancelado, requisição encerrada); `ApiCallTimeoutException`/`ApiCallAttemptTimeoutException` que disparam com menos de 10 ms de prazo restante, porque o `apiCallTimeout` de cada requisição é encurtado para o que resta do prazo (ADR-0006, ADR-0007) e, num 504, as chamadas em andamento não podem virar N falhas; falta de conexão livre no pool do cliente HTTP (`ConnectionRequestTimeoutException`, saturação da aplicação, vira 503 com `Retry-After`); e 4xx que não são throttling (`ValidationException` etc., a requisição é que estava errada, continua 502). Throttling conta: depois do retry do SDK vira 503 com `Retry-After` (`VisaoConsolidadaSobrecarregadaException`), e as `UnprocessedKeys` que sobram depois das 3 leituras também, lançadas dentro do circuito na última leitura. Antes elas saíam como 502 fora do circuito e throttling persistente nunca o abria.

Os lotes de `BatchGetItem` de uma consulta CLIENTE/CONTA eram todos disparados ao mesmo tempo: 500 operações × 8 blocos = 40 lotes simultâneos numa consulta, contra um pool de 100 conexões com 20 ms de espera e um bulkhead de 200 consultas. O pool saturava, a espera por conexão virava 502, o circuito abria e derrubava também OPERACAO. Agora cada consulta tem no máximo `consultas.dynamodb.max-lotes-simultaneos` (4) lotes em andamento, e o pool é dimensionado pelo pior caso dos bulkheads, validado na subida:

```
max-conexoes ≥ (bulkhead dynamodb − bulkhead dynamodb-cliente-conta) + bulkhead dynamodb-cliente-conta × max-lotes-simultaneos
300          ≥ (180 − 40) + 40 × 4
```

OPERACAO faz uma chamada por vez (um lote); CLIENTE/CONTA fazem a `Query` antes dos lotes. Com isso, sem falha do DynamoDB, nenhuma consulta admitida pelos bulkheads espera conexão. Os lotes são esperados na ordem em que terminam (`ExecutorCompletionService`): o primeiro que falhar derruba a consulta e interrompe os demais, sem esperar os anteriores.

Item da tabela ou do GSI fora do formato (sem `numero_operacao`, `custodia_expand` ou `texto_detalhe`, bloco desconhecido, `texto_detalhe` que não é JSON em GZIP) é dado inválido na fonte: 502 (`VisaoConsolidadaInvalidaException`), fora do circuito (o DynamoDB respondeu).

## Considered Options

Limitar só o bulkhead total ao tamanho do pool (200 → 100) deixaria o fan-out de lotes sem teto: uma única consulta CLIENTE grande ainda ocuparia 40 conexões. Rejeitado em favor do teto por consulta mais a regra de dimensionamento.
