# Tabela DynamoDB — Expand de Operações de Consignado

23/11/2026 · Pedro

## Visão geral

A tabela guarda, para cada operação de crédito, uma coleção de 8 itens — um por bloco de dados (operação, originação, componentes, parcelas, amortizações, recebimentos, plano e saldo). Todos os itens de uma operação compartilham a mesma PK, então uma única `Query` traz a operação inteira.

O conteúdo de negócio de cada item fica no atributo `texto_detalhe`, um JSON comprimido em GZIP. Os demais atributos servem de chave, índice ou controle. Dois GSIs permitem chegar às operações a partir do CPF/CNPJ do cliente ou da conta.

**Nome da tabela:** a definir. **Modo de capacidade e TTL:** não informados.

## Esquema de chaves

A chave primária é composta: `numero_operacao` (PK) + `custodia_expand` (SK). Os dois GSIs são esparsos e usam `sk_gsi` como sort key (ADR-0001): só o item `OPERACAO` carrega `cpf_cnpj_cliente`, `conta_cliente` e `sk_gsi`, então cada GSI tem um item por operação e custódia, já ordenável por data de contratação.

| Índice | Partition key | Sort key | Uso |
| --- | --- | --- | --- |
| Tabela base | `numero_operacao` (N) | `custodia_expand` (S) | Ler os blocos de uma operação (`BatchGetItem` por custódia × bloco) |
| `gsi-cliente` | `cpf_cnpj_cliente` (S) | `sk_gsi` (S) | Listar as operações de um cliente, da mais recente para a mais antiga |
| `gsi-conta` | `conta_cliente` (S) | `sk_gsi` (S) | Listar as operações de uma conta, da mais recente para a mais antiga |

A SK segue o padrão `<CUSTODIA>#EXPAND#<BLOCO>`, com a custódia em que a operação está registrada: `SF`, `F5`, `OD` ou `NSF` (ADR-0012). Ex.: `SF#EXPAND#OPERACAO`, `F5#EXPAND#PARCELAS`, `NSF#EXPAND#SALDO`. Uma operação vive em uma custódia; se aparecer em mais de uma (duplicidade), vale a precedência NSF > SF > F5 > OD e todos os blocos vêm da vencedora. Nas consultas por cliente ou conta, a custódia de cada operação vem do `custodia_expand` do item projetado no GSI.

`sk_gsi` = `<data_contratacao_operacao>#<numero_operacao>` (ex.: `2026-05-21#100000001`). Os GSIs projetam `ALL`.

Capacidade: a confirmar.

## Atributos

| Atributo | Tipo | Papel | Exemplo | Descrição |
| --- | --- | --- | --- | --- |
| `numero_operacao` | N | PK | 1234567 | Identificador da operação de crédito |
| `custodia_expand` | S | SK da tabela | `F5#EXPAND#PARCELAS` | Custódia e bloco de dados que o item guarda |
| `cpf_cnpj_cliente` | S | PK do `gsi-cliente` (só no item `OPERACAO`) | 12345678912 | CPF ou CNPJ do cliente, só dígitos, como string para manter zeros à esquerda |
| `conta_cliente` | S | PK do `gsi-conta` (só no item `OPERACAO`) | 434115000065874 | Conta do cliente |
| `sk_gsi` | S | SK dos dois GSIs (só no item `OPERACAO`) | `2026-05-21#100000001` | Data de contratação + número da operação |
| `codigo_expand` | N | Controle | 1 a 8 | Código numérico do bloco, um para um com a SK |
| `data_insercao` | S | Auditoria | 2026-11-01 00:00 | Data e hora da gravação do item |
| `data_alteração` | S | Auditoria | 2026-11-01 00:00 | Data e hora da última alteração do item |
| `texto_detalhe` | B | Payload | `H4sIAAAA…` | JSON do bloco comprimido em GZIP (aparece em Base64 no export) |

Os tipos de `numero_operacao` e `codigo_expand` foram inferidos dos valores de exemplo; confirme na definição da tabela.

## Tipos de item

Cada operação tem 8 itens por custódia (a massa local tem também o bloco 9, `CONTROLES_IOF`). O tamanho é do `texto_detalhe` nos dados de exemplo. `<C>` é a custódia (`SF`, `F5`, `OD` ou `NSF`).

| `codigo_expand` | `custodia_expand` (SK) | Conteúdo | Formato do JSON | JSON / GZIP (bytes) |
| --- | --- | --- | --- | --- |
| 1 | `<C>#EXPAND#OPERACAO` | Dados cadastrais da operação | Objeto | 154 / 135 |
| 2 | `<C>#EXPAND#ORIGINACAO` | Canal e dependência contábil da contratação (a F5 não tem dependência contábil) | Objeto | 83 / 94 |
| 3 | `<C>#EXPAND#COMPONENTES` | Componentes financeiros (liberação, IOF, seguro) | Array | 844 / 175 |
| 4 | `<C>#EXPAND#PARCELAS` | Cronograma de parcelas | Array | 1.115 / 203 |
| 5 | `<C>#EXPAND#AMORTIZACOES` | Amortizações aplicadas por parcela | Array | 1.197 / 222 |
| 6 | `<C>#EXPAND#RECEBIMENTOS` | Valores recebidos e tipo de baixa | Array | 678 / 218 |
| 7 | `<C>#EXPAND#PLANO` | Plano de amortização, taxas e encargos | Objeto aninhado | 384 / 239 |
| 8 | `<C>#EXPAND#SALDO` | Saldo devedor da operação e por parcela | Objeto aninhado | 668 / 185 |

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
| `dependencia_contabil` | string (zeros à esquerda), ausente na custódia F5 | 004 |

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
| Blocos de uma operação, custódia conhecida | Tabela base (`BatchGetItem`) | `numero_operacao = :op` e `custodia_expand IN ('<C>#EXPAND#OPERACAO', '<C>#EXPAND#SALDO', ...)` | 1 por bloco |
| Blocos de uma operação, custódia desconhecida | Tabela base (`BatchGetItem`) | as mesmas chaves nas 4 custódias (até 36 chaves, um lote) | 1 por bloco existente; duplicidade resolvida pela precedência |
| Operações de um cliente | `gsi-cliente` (`Query`, `ScanIndexForward=false`) | `cpf_cnpj_cliente = :cpf` | 1 item `OPERACAO` por operação e custódia, da mais recente para a mais antiga |
| Operações de uma conta | `gsi-conta` (`Query`, `ScanIndexForward=false`) | `conta_cliente = :conta` | Idem |
| Demais blocos das operações de um cliente ou conta | Tabela base (`BatchGetItem`, lotes de 100) | `numero_operacao` + `<C>#EXPAND#<BLOCO>`, com `<C>` lida do `custodia_expand` do item do GSI | 1 por operação × bloco |

Como os GSIs são esparsos e ordenados por `sk_gsi`, a `Query` devolve só os itens `OPERACAO`, já na ordem de contratação; `Limit` corta nas N mais recentes. Os demais blocos são lidos na tabela base. Uma operação duplicada em duas custódias aparece duas vezes no GSI (mesmo `sk_gsi`, `custodia_expand` diferente) e a API fica com a da custódia de maior precedência.

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
