---
status: accepted
---

# Cache versionado por operação, com Lua de chave única na gravação e marcador de invalidação

Substitui parcialmente o ADR-0011 (formato das chaves de bloco e a regra "sem EVAL").

As Atualizações Online e Offline regravam todos os blocos de uma operação numa custódia de uma vez (`TransactWriteItems`), com a mesma versão do registro (`codigo_identificador_carga`), e uma consulta nunca pode entregar blocos de versões diferentes da mesma operação. O cache por bloco (`op:{id}:{bloco}`, `SET ... PX` cego) não garantia isso: uma leitura que começou antes de uma atualização podia regravar o dado antigo logo depois da invalidação, e blocos de versões diferentes conviviam no cache até o TTL.

## Decisão

- **Formato.** Um hash por operação e custódia, `op:{id_operacao}:{custodia}`: campo `versao` (epoch ms), um campo por bloco (`texto_detalhe` em GZIP cru, como hoje) e, quando invalidado, o campo `marcador`. TTL de 5 min, definido só quando o hash é criado ou substituído, nunca renovado ao completar blocos: o TTL é a rede de segurança para uma invalidação perdida.
- **Leitura.** `HMGET` de `versao`, `marcador` e dos blocos pedidos, em pipeline (substitui o `MGET`). Um `HMGET` é atômico, então os blocos lidos são sempre da mesma versão. Hash com marcador conta como miss. Os limites do ADR-0011 (comandos por pipeline, máximo por consulta, prazo mínimo restante) continuam, recalibrados para `HMGET` por operação.
- **Gravação.** Continua fora da requisição, agora por um `EVALSHA` de **uma única chave** (compatível com cluster mode), que compara a versão do leitor com a do hash:
  - ausente → grava o hash e o TTL;
  - menor → apaga e regrava o hash com o TTL;
  - igual → completa só os blocos que faltam (outro `expand`), sem tocar no TTL;
  - maior → rejeita (o leitor entrega o que leu e não grava).
- **Versão na leitura do DynamoDB.** A API projeta `codigo_identificador_carga` e o converte para epoch ms por conta própria (a coluna `codigo_identificador_carga_epoch` é dos escritores, para a ConditionExpression). Ela confere se todos os blocos de cada operação, inclusive o que veio do GSI, têm a mesma versão. O `BatchGetItem` não é isolado das transações, e as leituras eventualmente consistentes e o GSI podem estar atrasados. Se houver divergência, a API relê aquela operação com `ConsistentRead=true`, no máximo 2 vezes, dentro do prazo da requisição. Se a divergência persistir ou o prazo acabar, a resposta é **503**, nas três consultas (OPERACAO, CLIENTE e CONTA): omitir a operação de uma lista seria uma resposta errada e silenciosa. Se a versão estiver ausente ou fora do formato, a API não grava no cache e entrega o dado se os valores brutos dos blocos forem iguais; senão, 503.
- **Marcador de invalidação.** O invalidador é uma Lambda num repositório próprio (`consultas-operacoes-credito-trigger-invalidador-cache`), ligada ao DynamoDB Streams com `NEW_IMAGE`, o ponto único para os dois tipos de atualização. Para cada operação gravada na versão V, ela roda um Lua de chave única: "se `versao` < V ou ausente, apaga os blocos, grava `versao=V` e `marcador=1` e aplica o TTL". Um leitor só substitui o marcador com versão ≥ V, então uma leitura atrasada com o dado antigo é rejeitada mesmo depois do marcador gravado. As 9 linhas de uma transação geram 9 registros com a mesma V, e as gravações repetidas são idempotentes. REMOVE vira `DEL`, e a defasagem residual de uma remoção fica limitada pelo TTL.
- **Cache de custódia versionado.** A visão consolidada passa a ler sua própria chave, o hash `custodia-vc:{id_operacao}`, com os campos `custodia`, `versao` e, quando invalidado, `marcador`, e TTL de 24 h. Ali, `versao` é a versão do registro da operação na custódia vencedora. A gravação usa o mesmo Lua de chave única e a mesma regra de versão (ausente ou menor substitui, maior rejeita). O invalidador grava o marcador com a versão V da linha em INSERT, que é o evento que pode mudar a custódia vencedora, e apaga a chave em REMOVE. Isso funciona porque, numa migração, a versão na custódia de destino é sempre maior que a da origem: um leitor que descobriu a custódia antiga é rejeitado. A chave compartilhada `custodia:{id_operacao}` (contrato com a fonte primária, ADR-0017) não muda: a visão consolidada continua gravando-a como hoje, mas não a lê mais. A fonte primária grava essa chave sem versão, a partir do DB2, e não poderia participar da regra.
- **Eviction.** `maxmemory-policy allkeys-lru` e alarme para `evicted_keys > 0`. `volatile-ttl` está proibido, porque despejaria primeiro os marcadores. No pior caso, um marcador despejado deixa dado antigo, mas coerente entre blocos, até o TTL.
- **Rollout.** Primeiro a API (as chaves antigas `op:{id}:{bloco}` expiram sozinhas e não colidem com as novas), depois os escritores (coluna epoch) e por último o invalidador. Até o invalidador entrar, a defasagem continua limitada a 5 min, mas sem mistura de versões.

## Considered Options

- **`DEL` simples na invalidação.** É a causa do problema: o leitor atrasado regrava o dado antigo. Rejeitado.
- **Marcador bloqueante por tempo, sem versão** (`SET NX` do leitor, marcador de 5 s, leitor descarta a gravação se passou 1 s desde que começou a ler o Dynamo). Não precisa de Lua e respeitaria o ADR-0011. Mas não garante a versão única entre blocos e deixa cada chave sem cache por 5 s depois de cada atualização. Rejeitado.
- **Versão autoritativa no DynamoDB ou linha de controle por operação.** Nenhuma ConditionExpression torna atômico o `SET` no Valkey, e verificar depois custaria uma leitura consistente por miss. Com `TransactWriteItems` regravando todos os blocos, a versão já é a mesma em todas as linhas, e a linha de controle é dispensável. Rejeitado.
- **Chave por bloco com a versão num envelope binário.** Um `MGET` de chaves diferentes não é atômico entre blocos, então a mistura de versões continuaria possível na leitura. Rejeitado em favor do hash.
- **`TransactGetItems` na leitura.** Custa o dobro de RCU em toda leitura, aceita no máximo 100 itens por chamada e não cobre a `Query` no GSI. Rejeitado em favor da verificação otimista.
- **Versionar a própria `custodia:{id}`.** Quebraria o contrato do ADR-0017, e a fonte primária, que grava sem versão, furaria a regra. Apenas apagá-la em INSERT deixaria a corrida aberta, com a custódia errada por até 24 h. Rejeitado em favor de uma chave própria.
- **Lambda → SQS → ECS no invalidador.** Acrescenta um salto ao orçamento de 2 s e mais um componente para operar. Uma Lambda por shard, com `ParallelizationFactor` de até 10 e lotes grandes em pipeline, deve acompanhar a taxa de escrita do Dynamo. Rejeitado por ora: reavaliar se o alarme de `IteratorAge` disparar.
