# Cache no Valkey em comandos pequenos, gravação fora da requisição e sem cluster mode

> Parcialmente substituído pelo ADR-0018: as chaves de bloco viraram um hash versionado por operação (`HMGET` na leitura), e a gravação usa um `EVALSHA` de chave única.

O cache por bloco (ADR-0002) lia todas as chaves de uma consulta num único `MGET` e gravava com um único `EVAL` que fazia um `SET` por bloco, sob um timeout de 10 ms. Numa consulta CLIENTE com 500 operações × 8 blocos (4.000 chaves) o comando sempre estourava o timeout, abria o circuito `valkey` para todas as consultas, e o script gigante segurava o Valkey (single-thread) para os demais clientes.

Agora a leitura é feita em `MGET`s de até `consultas.cache.chaves-por-comando` (100) chaves, enviados em pipeline pela conexão compartilhada do Lettuce e esperados juntos pelo timeout de comando. A gravação são `SET ... PX` de uma chave, em rodadas de até 100 em pipeline, e sai **fora da requisição** (virtual thread, no máximo `max-gravacoes-simultaneas` ao mesmo tempo; sem vaga, a gravação é descartada): a resposta não espera o cache. Consultas com mais de `max-chaves-por-consulta` (500) chaves nem leem nem gravam no cache e vão direto ao DynamoDB (`consultas.cache{resultado=ignorado}`): o ganho do cache nelas não paga o risco de comandos lentos. A leitura também é pulada quando resta menos que `prazo-minimo-restante` (20 ms, acima do timeout de comando de 10 ms) do prazo da requisição, assim um timeout do Valkey nunca é encurtado pelo prazo e o circuito só conta lentidão real do Valkey.

O `connect-timeout` caiu de 100 ms para 20 ms: um host inalcançável (pacotes descartados, não recusados) custava 100 ms por consulta até o circuito abrir, e de novo a cada meio-aberto, dois terços do prazo de OPERACAO.

Valkey fora, lento ou com o circuito aberto passa a contar `consultas.cache{resultado=indisponivel}` em vez de `miss`, para que a taxa de acerto não caia por causa de indisponibilidade. O evento de consumo (`cache_misses`) não muda: lá indisponível e ignorado continuam contando como miss.

**Premissa de implantação**: Valkey/ElastiCache **standalone ou replication group com cluster mode desligado** (`spring.data.redis.host`), como no ambiente local. As chaves `op:{id_operacao}:{bloco}` não têm hash tag. Mesmo assim, o código já não depende disso: não há mais script nem transação com várias chaves (o `EVAL` multi-chave daria `CROSSSLOT` em cluster mode), só comandos de uma chave e `MGET`, que o cliente de cluster do Lettuce (`spring.data.redis.cluster.nodes`) divide por slot. Ligar o cluster mode é uma mudança de configuração, a validar com teste de carga (um `MGET` de 100 chaves vira até 100 comandos em nós diferentes).

## Considered Options

Hash tag por operação (`op:{100000001}:parcelas`) poria os blocos de uma operação no mesmo slot, mas uma consulta CLIENTE junta operações diferentes, então o `MGET` continuaria multi-slot; não resolve o caso que importa. Rejeitado.

Manter a gravação na requisição, só dividida em rodadas, ainda somaria até um timeout de comando por rodada à latência da consulta. Rejeitado em favor da gravação fora da requisição, aceitável porque o cache é só otimização.
