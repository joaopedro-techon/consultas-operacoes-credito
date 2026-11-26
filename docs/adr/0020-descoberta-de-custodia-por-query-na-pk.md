---
status: accepted
---

# Descoberta de custódia por `Query` na PK, com a capacidade consumida medida

Revisa o ADR-0012 só na visão consolidada e só na consulta OPERACAO sem custódia conhecida. A precedência, o cache de custódia e a regra de versão não mudam.

Sem custódia em cache, a API pedia num `BatchGetItem` cada bloco solicitado nas quatro custódias: até 36 chaves. O `BatchGetItem` cobra cada chave pedida separadamente, arredondada a 4 KB, **inclusive as que não existem** (mínimo de 0,5 RRU em leitura eventual). Uma operação vive em uma custódia, então a maior parte dessas chaves não existe, mas é cobrada mesmo assim: até 18 RRU por descoberta, e também por operação inexistente (404), que não tem cache negativo. Já a `Query` cobra a soma do tamanho dos itens que existem, arredondada a 4 KB uma única vez. Com blocos de algumas centenas de bytes em GZIP, a operação inteira costuma custar 0,5 RRU.

## Decisão

- **Descoberta.** Uma `Query` na tabela base com `numero_operacao = :id`, leitura eventual (como antes), paginada pelo `LastEvaluatedKey`. Ela traz todos os itens da operação em todas as custódias. A API fica só com as chaves que o `BatchGetItem` pediria (blocos solicitados × custódias conhecidas) e descarta o resto sem decodificar. A precedência, o cache e a releitura consistente seguem iguais (`VisaoConsolidadaDynamoDbAdapter#lerEmTodasAsCustodias`).
- **Bloco desconhecido na partição é ignorado, não 502.** O `BatchGetItem` só devolvia chaves pedidas. A `Query` pode trazer um bloco que um escritor já grava e esta versão da API ainda não conhece. Esse item não foi pedido, então não é dado inválido. No `BatchGetItem` (custódia conhecida), bloco desconhecido continua 502.
- **O que continua com `BatchGetItem`:**
  - custódia conhecida, porque pede só os blocos ausentes do cache, e a `Query` releria também os que estão no cache;
  - releitura consistente, pelo mesmo motivo;
  - os blocos de CLIENTE/CONTA, porque uma `Query` por operação custa menos RRU, mas multiplica as chamadas e não cabe no prazo de 400 ms com listas grandes.
- **Capacidade medida.** O `CapacidadeConsumidaInterceptor` pede `ReturnConsumedCapacity=TOTAL` em toda `Query` e todo `BatchGetItem`, sem custo extra. Ele registra `consultas.dynamodb.capacidade_consumida` (DistributionSummary, tags `operacao` e `indice`; `indice=nenhum` é a tabela base). Com ela, a descoberta (`Query`/`nenhum`), a listagem (`Query`/`gsi-*`) e os blocos (`BatchGetItem`/`nenhum`) têm custo médio e máximo por chamada com dados reais.

## Consequências

- A descoberta e o 404 deixam de pagar pelas custódias vazias: de até 18 RRU para, em geral, 0,5 RRU. Também é uma chamada só, como antes.
- **A `Query` lê todos os blocos da custódia, mesmo que o `expand` peça dois.** Com blocos reais grandes (listas de parcelas, amortizações e recebimentos crescem com o tempo), a soma pode passar de 4 KB e custar mais que 0,5 RRU. Mesmo assim, ela não passa do `BatchGetItem` que substitui: ele cobrava o mínimo de 0,5 RRU por chave nas quatro custódias. Por isso a troca vale em qualquer tamanho de bloco.
- Se um bloco um dia precisar ser dividido em vários itens por causa do limite de 400 KB (`<C>#EXPAND#<BLOCO>#0001`…), a `Query` já é o padrão de leitura que traz os pedaços sem saber quantos são.
- A estimativa de RRU de `docs/capacidade-ecs-fargate.md` (seção 3.5) para OPERACAO sem custódia em cache fica conservadora. Recalibrar com a métrica.

## Considered Options

- **Manter o `BatchGetItem` nas quatro custódias** (ADR-0012). É o mesmo código da custódia conhecida, mas paga pelas chaves que não existem. Rejeitado: o argumento "caminho de leitura diferente" se resolve com um método que devolve o mesmo `Map<Custodia, Map<Bloco, ItemLido>>`.
- **`Query` também com custódia conhecida** (`begins_with(custodia_expand, '<C>#')`). Releria blocos que já estão no cache e, com blocos grandes, pode custar mais que pedir só os ausentes. Rejeitado por ora: reavaliar com a métrica de capacidade.
- **Uma `Query` por operação em CLIENTE/CONTA, ou um caminho para cada tamanho de lista.** Gasta menos RRU, mas são até 500 chamadas por consulta contra 4 lotes simultâneos e o prazo de 400 ms. Adiado: depende do tamanho real dos blocos, que a métrica vai mostrar.
- **Mudar a modelagem** (originação no item `OPERACAO`, ou um item por operação e custódia). Rejeitado: impacta os escritores e o invalidador, e um item agregado corre risco de passar de 400 KB com dados reais.
