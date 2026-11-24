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
Cópia temporária (Valkey, TTL) do `texto_detalhe` de cada bloco de uma operação já lido da visão consolidada, com chave `op:{id_operacao}:{bloco}`. Só otimização: indisponível, a consulta segue no DynamoDB (fail-open). Não guarda a lista de operações de um cliente ou conta e não vale para a fonte primária (ADR-0002).
_Avoid_: cache de consulta, cache de resposta

**Cache de custódia**:
Lembrança temporária (Valkey, TTL) de em qual custódia uma operação foi encontrada, usada pelas duas rotas para pular a descoberta de custódia. Guarda só a custódia, nunca dados da operação; se a custódia lembrada não tem mais a operação, a lembrança é descartada e a descoberta é refeita (ADR-0012).

**Fonte primária**:
Sistema de origem das operações (mainframe DB2), consultado quando se precisa do dado sem defasagem. Fica aqui só como referência: quem a consulta é outro serviço, em outro repositório (`consultas-operacoes-credito-mainframe`, ADR-0017).
_Avoid_: origem (confunde com Originação)

**Consumidor**:
Sistema que chama a API, identificado pela sua chave de acesso.
_Avoid_: cliente (reservado ao titular da operação), usuário

**Evento de consumo**:
Registro de uma consulta feita por um consumidor (o que pediu, o que recebeu, quanto demorou), destinado à análise de uso da API.
_Avoid_: log de acesso, evento de auditoria
