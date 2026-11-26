---
status: accepted
---

# Regra de versão dos blocos configurável: versão única ou versão por bloco

Substitui parcialmente o ADR-0018: a versão única entre os blocos de uma operação deixa de ser obrigatória e vira uma escolha de configuração.

Regravar todos os blocos a cada atualização desperdiça WCU. Quando o cliente paga uma parcela, mudam `parcelas`, `saldo`, `amortizacoes` e `recebimentos`, mas não a `originacao`. Entregar blocos de versões diferentes da mesma operação é aceitável para o negócio. Mesmo assim, a versão única continua disponível para quem precisar dela.

## Decisão

- **Flag.** `consultas.visao-consolidada.versao-dos-blocos = UNICA | POR_BLOCO`. O valor é estático, lido na subida, e a regra ativa aparece num log INFO. O default é `POR_BLOCO`, porque é o modo tolerante e funciona com qualquer escritor.
- **`UNICA`.** É exatamente o ADR-0018: verificação de versão única, até 2 releituras com `ConsistentRead`, 503 se a divergência persistir e o hash `op:{id}:{custodia}` com uma `versao` só. **Só vale com escritores que regravam a operação inteira** (`TransactWriteItems` de todos os blocos). Com escritores parciais, toda operação atualizada parcialmente vira 503, uma configuração inválida.
- **`POR_BLOCO`, leitura.** Não há verificação de versão, nem releitura, nem `ConsistentRead`, nem 503 por divergência, e a métrica `consultas.versao_divergente` não é calculada. Fica aceito que, por um instante, a resposta traga só parte dos blocos de uma mesma atualização (`parcelas` nova e `saldo` antigo): o `BatchGetItem` não é isolado das transações, e nada no dado diz quais blocos foram gravados juntos. Um bloco sem versão em epoch ms é entregue, mas só ele deixa de ir ao cache.
- **`POR_BLOCO`, cache de blocos.** Usa um hash próprio, `opb:{id_operacao}:{custodia}`, com os campos `{bloco}`, `v:{bloco}` (epoch ms) e, quando invalidado, `m:{bloco}`. Com o prefixo próprio, os dois formatos nunca colidem quando a flag muda. Um Lua de chave única grava bloco a bloco: versão ausente, menor ou igual com marcador substitui o bloco e apaga o marcador; igual sem marcador mantém; maior rejeita só aquele bloco. O TTL de 5 min só é aplicado quando o hash é criado e nunca é renovado. A leitura aceita cada bloco sem marcador e não compara a versão dele com a do GSI.
- **Cache de custódia, nos dois modos.** A chave `custodia-vc:{id}` é a mesma. A versão gravada é a do bloco `operacao`, e só a linha `...#EXPAND#OPERACAO` mexe nela no invalidador: o INSERT grava o marcador e o REMOVE apaga a chave. Em `UNICA` isso equivale à regra anterior, porque todos os blocos têm a mesma versão. Em `POR_BLOCO`, evita que o INSERT de um bloco novo na mesma custódia (o primeiro `controles_iof`, por exemplo) deixe a custódia marcada com uma versão que o leitor nunca alcança, o que faria a descoberta rodar a cada consulta por 24 h.
- **Invalidador sem flag.** Ele sempre grava nos dois formatos. No `op:`, a regra continua a do ADR-0018. No `opb:`, agrupa e marca por `(id, custodia, bloco)`, e o REMOVE de uma linha apaga só aquele bloco. Assim a troca da flag na API não abre janela sem invalidação e o rollout não depende de ordem. O custo é dobrar os comandos no Valkey por registro do Streams.
- **Contrato com os escritores (fora deste repo).** Em `POR_BLOCO`, cada escritor grava só as linhas dos blocos que mudaram, com `ConditionExpression` por linha (`codigo_identificador_carga_epoch < :v`). Numa migração de custódia, ele grava todos os blocos no destino, inclusive `OPERACAO`, e só depois remove a origem.

## Considered Options

- **Remover a versão única.** Rejeitado a pedido: a regra continua útil para quem regrava tudo e exige coerência entre blocos.
- **Hash único com `versao` = maior versão entre os blocos.** É mais simples, mas toda atualização parcial invalida todos os blocos e o filtro do CLIENTE/CONTA pela versão do GSI deixa de funcionar. Rejeitado: só troca o desperdício de WCU por um desperdício de cache.
- **"Grupo de atualização" gravado pelos escritores**, para a API conferir a coerência entre os blocos gravados juntos. Mexe no contrato com os escritores para fechar uma janela de milissegundos que o negócio aceita. Rejeitado.
- **Flag também no invalidador.** Exigiria coordenar dois deploys, e cada troca abriria uma janela sem invalidação. Rejeitado em favor de marcar os dois formatos.
