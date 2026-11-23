# Tabela DynamoDB — Expand de Operações de Consignado

23/11/2026 · Pedro

## Visão geral

A tabela guarda, para cada operação de crédito, uma coleção de 8 itens — um por bloco de dados (operação, originação, componentes, parcelas, amortizações, recebimentos, plano e saldo). Todos os itens de uma operação compartilham a mesma PK, então uma única `Query` traz a operação inteira.

O conteúdo de negócio de cada item fica no atributo `texto_detalhe`, um JSON comprimido em GZIP. Os demais atributos servem de chave, índice ou controle. Dois GSIs permitem chegar às operações a partir do CPF/CNPJ do cliente ou da conta.

**Nome da tabela:** a definir. **Modo de capacidade e TTL:** não informados.

## Esquema de chaves

A chave primária é composta: `numero_operacao` (PK) + `custodia_expand` (SK). Os dois GSIs também usam `custodia_expand` como sort key, então permitem escolher o bloco na própria condição de chave.

| Índice | Partition key | Sort key | Uso |
| --- | --- | --- | --- |
| Tabela base | `numero_operacao` (N) | `custodia_expand` (S) | Ler a operação inteira ou um bloco específico |
| GSI por CPF/CNPJ | `cpf_cnpj_cliente` (S) | `custodia_expand` (S) | Listar as operações ou um bloco de todas as operações de um cliente |
| GSI por conta | `conta_cliente` (S) | `custodia_expand` (S) | Listar as operações ou um bloco de todas as operações de uma conta |

A SK segue o padrão `SF#EXPAND#<BLOCO>`. O prefixo comum permite usar `begins_with(custodia_expand, 'SF#EXPAND#')` para trazer todos os blocos, e igualdade para trazer um só.

Nomes dos GSIs, projeção (`ALL`, `KEYS_ONLY` ou `INCLUDE`) e capacidade: a confirmar.

## Atributos

| Atributo | Tipo | Papel | Exemplo | Descrição |
| --- | --- | --- | --- | --- |
| `numero_operacao` | N | PK | 1234567 | Identificador da operação de crédito |
| `custodia_expand` | S | SK da tabela e dos dois GSIs | `SF#EXPAND#PARCELAS` | Bloco de dados que o item guarda |
| `cpf_cnpj_cliente` | S | PK do GSI por CPF/CNPJ | 12345678912 | CPF ou CNPJ do cliente, só dígitos, como string para manter zeros à esquerda |
| `conta_cliente` | S | PK do GSI por conta | 434115000065874 | Conta do cliente |
| `codigo_expand` | N | Controle | 1 a 8 | Código numérico do bloco, um para um com a SK |
| `data_insercao` | S | Auditoria | 2026-11-01 00:00 | Data e hora da gravação do item |
| `data_alteração` | S | Auditoria | 2026-11-01 00:00 | Data e hora da última alteração do item |
| `texto_detalhe` | B | Payload | `H4sIAAAA…` | JSON do bloco comprimido em GZIP (aparece em Base64 no export) |

Os tipos de `numero_operacao` e `codigo_expand` foram inferidos dos valores de exemplo; confirme na definição da tabela.

## Tipos de item

Cada operação tem 8 itens. O tamanho é do `texto_detalhe` nos dados de exemplo.

| `codigo_expand` | `custodia_expand` (SK) | Conteúdo | Formato do JSON | JSON / GZIP (bytes) |
| --- | --- | --- | --- | --- |
| 1 | `SF#EXPAND#OPERACAO` | Dados cadastrais da operação | Objeto | 154 / 135 |
| 2 | `SF#EXPAND#ORIGINACAO` | Canal e dependência contábil da contratação | Objeto | 83 / 94 |
| 3 | `SF#EXPAND#COMPONENTES` | Componentes financeiros (liberação, IOF, seguro) | Array | 844 / 175 |
| 4 | `SF#EXPAND#PARCELAS` | Cronograma de parcelas | Array | 1.115 / 203 |
| 5 | `SF#EXPAND#AMORTIZACOES` | Amortizações aplicadas por parcela | Array | 1.197 / 222 |
| 6 | `SF#EXPAND#RECEBIMENTOS` | Valores recebidos e tipo de baixa | Array | 678 / 218 |
| 7 | `SF#EXPAND#PLANO` | Plano de amortização, taxas e encargos | Objeto aninhado | 384 / 239 |
| 8 | `SF#EXPAND#SALDO` | Saldo devedor da operação e por parcela | Objeto aninhado | 668 / 185 |

## Estrutura dos payloads (`texto_detalhe`)

Todos os blocos repetem `id_operacao`. Valores monetários e taxas são decimais; no Java, mapeie para `BigDecimal`.

### 1 · OPERACAO (objeto)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `situacao_operacao_credito` | string | ATIVA |
| `data_contratacao_operacao` | data (yyyy-MM-dd) | 2026-11-21 |
| `data_baixa_operacao` | data, pode ser `null` | null |
| `cpf_cnpj` | string | 12458965432 |

### 2 · ORIGINACAO (objeto)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `canal_contratacao` | string | MOBILE |
| `dependencia_contabil` | string (zeros à esquerda) | 004 |

### 3 · COMPONENTES (array)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `numero_componente_financeiro` | número | 1 |
| `codigo_tipo_componente_financeiro` | número | 1 a 4 |
| `tipo_componente_financeiro` | string | LIBERACAO, IOF, IOF_FLAT, SEGURO |
| `valor_componente_financeiro` | decimal | 75.0 |
| `situacao_componente_financeiro` | string | ATIVO |

### 4 · PARCELAS (array)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `numero_parcela_amortizacao` | número | 1 a 5 |
| `numero_plano_amortizacao` | número | 1 |
| `data_vencimento_parcela` | data (yyyy-MM-dd) | 2026-12-21 |
| `valor_principal_parcela` | decimal | 12.5 |
| `valor_juros_principal_parcela` | decimal | 10.0 |
| `situacao_parcela` | string | LIQUIDADA, ABERTA |

### 5 · AMORTIZACOES (array)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `numero_parcela_amortizacao` | número | 1 |
| `numero_plano_amortizacao` | número | 1 |
| `valor_principal_amortizado` | decimal | 12.5 |
| `valor_juros_principal_amortizado` | decimal | 10.0 |
| `indicador_ocorrencia_valida` | booleano | true |
| `data_valor_movimento` | data-hora sem fuso (yyyy-MM-ddTHH:mm:ss) | 2026-11-22T05:03:44 |

### 6 · RECEBIMENTOS (array)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `data_valor_movimento` | data-hora sem fuso | 2026-11-22T05:03:44 |
| `valor_recebido` | decimal | 45.0 |
| `tipo_baixa` | string | BAIXA_PARCIAL_PARCELA, BAIXA_INTEGRAL_MAIS_UMA_PARCELA |
| `motivo_baixa` | string | ANTECIPACAO_CLIENTE |

### 7 · PLANO (objeto aninhado)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `numero_plano_amortizacao_ativo` | número | 1 |
| `dados_plano_amortizacao[]` | array de planos | — |
| ↳ `taxa_juros_nominal` / `taxa_juros_efetiva` | decimal (%) | 2.0 |
| ↳ `situacao_plano_amortizacao` | string | ATIVO |
| ↳ `numero_plano_amortizacao_ativo` | número | 1 |
| ↳ `encargos_moratorios[]` | `dia_inicio`, `dia_fim`, `taxa_mora`, `taxa_multa` | 0, 9999, 5.0, 5.78 |
| ↳ `desconto_bom_pagador[]` | `quantidade_dias_atraso`, `percentual_desconto` | 99, 99.0 |

### 8 · SALDO (objeto aninhado)

| Campo | Tipo | Exemplo |
| --- | --- | --- |
| `id_operacao` | número | 123456789 |
| `saldo_devedor_operacao` | decimal | 7.5 |
| `quantidade_dias_atraso` | número | 0 |
| `data_posicao_calculo` | data (yyyy-MM-dd) | 2026-11-23 |
| `saldo_parcelas[]` | `id_operacao`, `numero_parcela_amortizacao`, `numero_plano_amortizacao`, `saldo_devedor` | 4, 1, 17.5 |

## Padrões de acesso

| Consulta | Índice | Condição de chave | Itens retornados |
| --- | --- | --- | --- |
| Operação completa | Tabela base | `numero_operacao = :op` | 8 |
| Um bloco da operação (ex.: saldo) | Tabela base (`GetItem`) | `numero_operacao = :op` e `custodia_expand = 'SF#EXPAND#SALDO'` | 1 |
| Operações de um cliente | GSI por CPF/CNPJ | `cpf_cnpj_cliente = :cpf` e `custodia_expand = 'SF#EXPAND#OPERACAO'` | 1 por operação |
| Um bloco de todas as operações de um cliente (ex.: saldos) | GSI por CPF/CNPJ | `cpf_cnpj_cliente = :cpf` e `custodia_expand = 'SF#EXPAND#SALDO'` | 1 por operação |
| Todos os blocos de um cliente | GSI por CPF/CNPJ | `cpf_cnpj_cliente = :cpf` | 8 por operação |
| Operações de uma conta | GSI por conta | `conta_cliente = :conta` e `custodia_expand = 'SF#EXPAND#OPERACAO'` | 1 por operação |
| Um bloco de todas as operações de uma conta | GSI por conta | `conta_cliente = :conta` e `custodia_expand = :bloco` | 1 por operação |

Com `custodia_expand` como sort key dos GSIs, o bloco é filtrado na própria condição de chave: a leitura consumida corresponde só aos itens retornados, sem `FilterExpression`. Para paginar operações de clientes com muitos contratos, use `Limit` e `ExclusiveStartKey`.

## Compressão do `texto_detalhe`

O JSON de cada bloco é minificado, comprimido com GZIP (`java.util.zip`) e gravado como atributo Binary. Nos arrays a redução fica entre 68% e 82%; nos objetos pequenos (OPERACAO, ORIGINACAO) o ganho é baixo ou negativo, por causa dos 18 bytes fixos do GZIP.

```java
public final class GzipCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public static AttributeValue toAttribute(Object obj) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(obj);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(json);
        }
        return AttributeValue.builder().b(SdkBytes.fromByteArray(bos.toByteArray())).build();
    }

    public static <T> T fromAttribute(AttributeValue av, TypeReference<T> type) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(av.b().asInputStream())) {
            return MAPPER.readValue(gzip, type);
        }
    }
}
```

Exemplo de leitura das parcelas: `GzipCodec.fromAttribute(item.get("texto_detalhe"), new TypeReference<List<Parcela>>() {})`.
