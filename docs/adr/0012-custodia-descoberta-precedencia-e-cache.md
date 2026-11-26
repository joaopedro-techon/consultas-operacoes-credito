# Custódia descoberta pela API, com precedência e cache de custódia

Uma operação está registrada em uma de quatro custódias (SF, F5, OD, NSF). O consumidor não informa qual, e a operação pode migrar de custódia. A API faz a descoberta sozinha, nas duas rotas, e devolve a custódia no campo `custodia` do bloco `operacao`.

- **Visão consolidada.** A SK é `<CUSTODIA>#EXPAND#<BLOCO>`.
  - Por operação: ~~o `BatchGetItem` pede os blocos nas quatro custódias (até 36 chaves, um lote só)~~. **Revisado pelo ADR-0020:** uma `Query` pela PK lê a operação em todas as custódias, sem pagar pelas chaves que não existem.
  - Por cliente ou conta: a custódia já vem na SK do item projetado no GSI.
- **Fonte primária.** Há um fan-out paralelo só do bloco `operacao` nas quatro custódias. Os demais blocos são lidos apenas na custódia encontrada.
- **Duplicidade.** Se a operação aparecer em mais de uma custódia, vale a ordem NSF > SF > F5 > OD (configurável). Todos os blocos vêm da vencedora, sem misturar custódias. A duplicidade gera log WARN e métrica.
- **Custódia indisponível.** Se uma custódia não pôde ser consultada e a operação não foi achada nas demais, a resposta é 503, não 404. Não se afirma que a operação não existe sem ter olhado em todas as custódias.

Para não pagar a descoberta a cada consulta, a custódia encontrada fica em Valkey (`custodia:{id_operacao}`, TTL de 24 h, fail-open).
- **Visão consolidada.** A chave entra no mesmo `MGET` dos blocos, sem hop extra, e corta o `BatchGetItem` de 4× para 1× blocos.
- **Fonte primária.** O cache poupa até 3 chamadas ao mainframe por consulta, o que é decisivo com a cota de 1.500 chamadas/s (ADR-0015).
- **Migração.** Se a custódia lembrada responder que não tem a operação, a chave é apagada e a descoberta completa é refeita uma vez, na mesma requisição. Nunca se cacheia "não encontrada".

Isso revisa o ADR-0002, que fica com uma exceção: a rota fonte primária continua sem cache de **dados**. A custódia é só um endereço, e um endereço desatualizado é detectado e corrigido na própria consulta, então não há risco de defasagem.

## Considered Options

- **Descoberta sequencial** (SF → F5 → OD → NSF): gasta menos chamadas, mas a latência piora até quatro vezes justamente para quem está no fim da ordem. Rejeitada.
- **Fan-out de todos os blocos em todas as custódias**: quadruplica as chamadas ao mainframe. Rejeitada.
- **No DynamoDB, uma `Query` pela PK** (todas as SKs da operação): também funciona, mas exigiria um caminho de leitura e de cache diferente do `BatchGetItem` atual. Rejeitada. *Adotada depois na descoberta, pelo ADR-0020, por custo: o `BatchGetItem` cobra cada chave inexistente.*
