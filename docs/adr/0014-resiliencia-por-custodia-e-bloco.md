# Resiliência da fonte primária por custódia e por bloco

O gateway mainframe é um host só, mas cada combinação de custódia e bloco (ou lista de operações) é um trancode diferente, que degrada de forma independente. Uma única instância `fontePrimaria` de circuito e retry fazia a falha da rota de parcelas do F5 abrir o circuito para todas as custódias.

Agora as instâncias são separadas assim:
- **Circuit breaker e retry** por rota: `fontePrimaria-<custodia>-<bloco>` e `fontePrimaria-<custodia>-lista_cliente` / `-lista_conta`, num total de 4 × 11 instâncias. São criadas sob demanda a partir da configuração base `fontePrimaria`, que pode ser sobrescrita por instância no `application.yml`. O nome da instância vai na tag `name` das métricas `resilience4j_*` e nos logs WARN de eventos.
- **Bulkhead** por custódia (`fontePrimaria-<custodia>`): o F5 lento não ocupa as vagas do SF. O bulkhead `fontePrimaria-cliente-conta`, que limita as consultas caras, continua por task.
- **Rate limiter** único por task (`fontePrimaria`). A cota é do mainframe inteiro, não de cada custódia (ADR-0015).

O DynamoDB não compartilha nenhuma instância com o mainframe: a limitação de um nunca recusa chamadas do outro. Um 429 do mainframe conta como falha da dependência (`FalhasDoGateway`) e é retentável com backoff. As regras do ADR-0009 sobre o que o circuito ignora continuam valendo para cada instância.
