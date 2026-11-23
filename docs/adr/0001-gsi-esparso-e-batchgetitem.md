# GSIs esparsos por cliente e conta, com leitura em dois passos

Cada operação tem um item por bloco na tabela (PK `numero_operacao`, SK `SF#EXPAND#<BLOCO>`). Só o item `OPERACAO` carrega `cpf_cnpj_cliente` e `conta_cliente`, então os GSIs por cliente e por conta têm um item por operação, com SK `<data_contratacao>#<numero_operacao>` e projeção `ALL`. Consultas CLIENTE e CONTA fazem uma `Query` no GSI (com `Limit` = `limite_operacoes`, mais recentes primeiro), que já devolve o bloco `operacao`, e depois um `BatchGetItem` na tabela base só com `originacao` e os demais blocos pedidos.

Preferimos isso a GSIs com todos os itens e projeção `ALL` porque essa alternativa triplica o storage do `texto_detalhe`, faz o `Limit` contar itens em vez de operações e não tem ordem entre operações. Como o GSI é esparso, a projeção `ALL` custa só o bloco `operacao` (~135 bytes por operação) e evita relê-lo. O preço é um round-trip extra (~10 ms), que cabe no P99 de 200 ms. O DynamoDB seleciona blocos; filtros sobre campos dentro de um bloco são aplicados em memória, porque o `texto_detalhe` é GZIP opaco.

## Considered Options

Guardar cada parcela (ou cada item de lista) como item próprio permitiria condição de chave por número de parcela, mas só para esse campo, não funciona no `BatchGetItem` e multiplica itens e custo de escrita para economizar a leitura de blocos de poucas centenas de bytes. Rejeitado.
