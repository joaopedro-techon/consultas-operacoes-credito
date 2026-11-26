# Consultas de Operações de Crédito — Custódia de Ativos PF

Consulta, somente leitura, das operações de crédito custodiadas e de seus dados financeiros (parcelas, amortizações, recebimentos, saldo etc.).

## Operação e partes

**Operação de crédito**:
Contrato de empréstimo de um cliente, identificado por `id_operacao`, com situação ATIVA, LIQUIDADA ou CANCELADA.
_Avoid_: contrato, empréstimo, operação (sozinho, em código)

**Cliente**:
Titular de operações de crédito, identificado por CPF ou CNPJ (`cpf_cnpj`).
_Avoid_: usuário, consumidor

**Conta**:
Conta do cliente à qual operações de crédito estão vinculadas.

**Originação**:
Circunstâncias da contratação da operação: canal de contratação e dependência contábil.

**Custódia**:
Sistema em que a operação de crédito está registrada: SF, F5, OD ou NSF. Cada operação está em uma única custódia, mas pode migrar de uma para outra ao longo da vida. O consumidor não informa a custódia; ela volta no campo `custodia` do bloco `operacao`.
_Avoid_: origem, carteira, sistema (sozinho)

**Precedência de custódia**:
Ordem que decide a custódia de uma operação encontrada em mais de uma ao mesmo tempo: NSF, depois SF, F5 e OD. Todos os blocos vêm da custódia que vence.

## Dados financeiros

**Componente financeiro**:
Parcela de valor que compõe a operação no momento da contratação: liberação de crédito, IOF, IOF flat, seguro, tarifa.

**Plano de amortização**:
Condições pactuadas para pagar a operação: taxas de juros nominal e efetiva, encargos moratórios (mora e multa) e desconto de bom pagador. Uma operação pode ter vários planos, um ativo.

**Parcela**:
Prestação do cronograma de um plano de amortização, com vencimento e situação ABERTA, LIQUIDADA ou REPACTUADA.
_Avoid_: prestação

**Recebimento**:
Entrada de dinheiro do cliente para a operação, com valor total recebido, tipo de baixa e motivo. Um recebimento gera uma ou mais amortizações.
_Avoid_: pagamento

**Amortização**:
Parte de um recebimento aplicada a uma parcela específica (principal e juros). Uma parcela pode ter várias amortizações, pois o cliente pode amortizar parcialmente.

**Controle de IOF**:
Registro de quanto IOF já foi pago para uma parcela. Uma parcela pode ter um ou mais controles de IOF.

**Saldo devedor**:
Valor ainda devido, por parcela e agregado na operação, em uma data de posição.

## Atualização da visão consolidada

**Atualização Online**:
Gravação na visão consolidada de um evento individual vindo da fonte primária (Kafka, entregue via SQS), em tempo quase real.
_Avoid_: evento online, carga online

**Atualização Offline**:
Gravação em massa na visão consolidada feita pela carga periódica (job Glue).
_Avoid_: carga online, batch (sozinho)

**Carga**:
Uma execução da Atualização Offline. Um evento da Atualização Online não é uma carga.

**Versão do registro**:
Instante (`codigo_identificador_carga`) que data o conteúdo de um bloco de uma operação de crédito em uma custódia. Vence a versão maior; versão igual ou menor é descartada. Como os blocos se relacionam com a versão depende da regra de versão dos blocos.
_Avoid_: timestamp, data de alteração

**Regra de versão dos blocos**:
Escolha de configuração que define se os blocos de uma operação compartilham a versão do registro:
- **Versão única**: toda atualização, Online ou Offline, regrava todos os blocos da operação com a mesma versão, e uma consulta nunca entrega blocos de versões diferentes da mesma operação. Só vale quando os escritores regravam a operação inteira.
- **Versão por bloco**: uma atualização regrava só os blocos que mudaram (um pagamento muda parcelas, saldo, amortizações e recebimentos, mas não a originação), e cada bloco tem a sua versão. Uma consulta pode entregar blocos de versões diferentes, inclusive, por um instante, parte dos blocos de uma mesma atualização.
_Avoid_: modo de consistência, versão mista

## Consulta

**Bloco**:
Cada um dos conjuntos de dados que a consulta pode trazer de uma operação: `operacao`, `originacao`, `componentes`, `parcelas`, `amortizacoes`, `recebimentos`, `plano`, `saldo`, `controles_iof`. Pedido pelo parâmetro `expand`; `operacao` e `originacao` sempre vêm.
_Avoid_: expand (como substantivo), domínio, seção

**Bloco indisponível**:
Bloco pedido que não pôde ser entregue para a operação: a visão consolidada ainda não o tem, ou a fonte primária falhou ao lê-lo enquanto os demais blocos responderam. Volta `null` e é listado no header `x-blocos-indisponiveis`; os outros blocos seguem na resposta. Um bloco-lista vazio não é indisponível, e um bloco-lista nunca volta truncado.

**Descoberta de custódia**:
Etapa que encontra em qual custódia a operação está, procurando-a em todas as custódias quando ainda não se sabe.

**Custódia indisponível**:
Custódia que não pôde ser consultada (falha ou proteção aberta). Se a operação não foi achada nas demais, não se pode afirmar que ela não existe: a consulta falha com 503, e não com 404.

**Catálogo de campos**:
Configuração que declara, por bloco, os campos válidos e o tipo de cada um (número, decimal, data, data-hora, texto, booleano, objeto, lista). Valida `fields`/`filter` e tipa as comparações do filtro (ADR-0005).
_Avoid_: schema, modelo

**Seleção de campos**:
Campos de cada bloco que o consumidor quer ver, pedidos em `fields` como `bloco.campo` (com ponto para campos aninhados). Bloco sem campos selecionados volta completo; `id_operacao` volta sempre.
_Avoid_: projeção, filtro de campos

**Tipo de consulta**:
Critério de busca: OPERACAO (por `id_operacao`), CLIENTE (por CPF/CNPJ) ou CONTA (por conta).

**Prazo da requisição**:
Tempo máximo que uma consulta pode levar, por tipo de consulta, contado desde a chegada da requisição; esgotado, a consulta falha com 504 (ADR-0006).
_Avoid_: timeout (reservado aos limites de conexão, pool e resposta de uma chamada)

**Visão consolidada**:
Cópia de leitura das operações, otimizada para rapidez e eventualmente consistente com a fonte primária.

**Cache de blocos**:
Cópia temporária (Valkey, TTL) dos blocos de uma operação em uma custódia já lidos da visão consolidada, Com versão única, todos os blocos são da mesma versão do registro, e o cache nunca guarda blocos de versões diferentes (ADR-0018). Com versão por bloco, cada bloco tem a sua versão e é substituído ou invalidado sozinho. Nas duas regras, nunca troca uma versão por outra menor. Só otimização: indisponível, a consulta segue no DynamoDB (fail-open). Não guarda a lista de operações de um cliente ou conta e não vale para a fonte primária (ADR-0002).
_Avoid_: cache de consulta, cache de resposta

**Marcador de invalidação**:
Registro, no cache de blocos, de que a operação foi atualizada para uma versão do registro (com versão por bloco, de que aquele bloco foi atualizado): tira os blocos antigos de circulação e impede que uma leitura com versão menor os traga de volta (ADR-0018).
_Avoid_: tombstone, invalidação (sozinho)

**Cache de custódia**:
Lembrança temporária (Valkey, TTL) de em qual custódia uma operação foi encontrada, usada para pular a descoberta de custódia. Guarda só a custódia, nunca dados da operação; se a custódia lembrada não tem mais a operação, a lembrança é descartada e a descoberta é refeita (ADR-0012). A visão consolidada mantém a sua lembrança junto com a versão do registro, e uma lembrança de versão menor nunca substitui uma de versão maior; a lembrança sem versão, compartilhada com a fonte primária, ela só alimenta (ADR-0018).

**Fonte primária**:
Sistema de origem das operações (mainframe DB2), consultado quando se precisa do dado sem defasagem. Fica aqui só como referência: quem a consulta é outro serviço, em outro repositório (`consultas-operacoes-credito-mainframe`, ADR-0017).
_Avoid_: origem (confunde com Originação)

**Consumidor**:
Sistema que chama a API, identificado pela sua chave de acesso.
_Avoid_: cliente (reservado ao titular da operação), usuário

**Evento de consumo**:
Registro de uma consulta feita por um consumidor (o que pediu, o que recebeu, quanto demorou), destinado à análise de uso da API.
_Avoid_: log de acesso, evento de auditoria
