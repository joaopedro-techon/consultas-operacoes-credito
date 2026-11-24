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
Bloco pedido que a visão consolidada ainda não tem para a operação; volta `null` e é listado no header `x-blocos-indisponiveis`. Um bloco-lista vazio não é indisponível.

**Catálogo de campos**:
Configuração que declara, por bloco, os campos válidos e o tipo de cada um (número, decimal, data, data-hora, texto, booleano, objeto, lista). Valida `fields`/`filter` e tipa as comparações do filtro (ADR-0005).
_Avoid_: schema, modelo

**Seleção de campos**:
Campos de cada bloco que o consumidor quer ver, pedidos em `fields` como `bloco.campo` (com ponto para campos aninhados). Bloco sem campos selecionados volta completo; `id_operacao` volta sempre.
_Avoid_: projeção, filtro de campos

**Tipo de consulta**:
Critério de busca: OPERACAO (por `id_operacao`), CLIENTE (por CPF/CNPJ) ou CONTA (por conta).

**Visão consolidada**:
Cópia de leitura das operações, otimizada para rapidez e eventualmente consistente com a fonte primária.

**Cache de blocos**:
Cópia temporária (Valkey, TTL) do `texto_detalhe` de cada bloco de uma operação já lido da visão consolidada, com chave `op:{id_operacao}:{bloco}`. Só otimização: indisponível, a consulta segue no DynamoDB (fail-open). Não guarda a lista de operações de um cliente ou conta e não vale para a fonte primária (ADR-0002).
_Avoid_: cache de consulta, cache de resposta

**Fonte primária**:
Sistema de origem das operações (mainframe DB2), consultado quando se precisa do dado sem defasagem.
_Avoid_: origem (confunde com Originação)

**Trancode**:
Texto de posições fixas devolvido pela fonte primária, em que cada faixa de posições representa um campo de um bloco.

**Consumidor**:
Sistema que chama a API, identificado pela sua chave de acesso.
_Avoid_: cliente (reservado ao titular da operação), usuário

**Evento de consumo**:
Registro de uma consulta feita por um consumidor (o que pediu, o que recebeu, quanto demorou), destinado à análise de uso da API.
_Avoid_: log de acesso, evento de auditoria
