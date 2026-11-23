# API de Consulta de Operações de Crédito — Custódia de Ativos PF

Versão revisada de `prompt_detalhes_1.md`, com as contradições resolvidas e as decisões tomadas. Em caso de conflito, este documento prevalece sobre o v1 e sobre `tabela-dynamodb-expand-operacoes-consignado.md`.

Leia antes de implementar:

- `GLOSSARY.md`: vocabulário do domínio. Use esses termos em código, testes, logs e issues.
- `docs/adr/`: decisões e o motivo de cada uma (ADR-0001 a 0005).
- `docs/tabela-dynamodb-expand-operacoes-consignado.md`: schema dos payloads de cada bloco (seções "Estrutura dos payloads"). O esquema de chaves e os padrões de acesso desse documento foram **substituídos** pela seção 4 abaixo.
- `docs/exemplo_operacao.json`: formato de resposta.

## 1. Objetivo

API REST, somente leitura, que devolve uma ou mais operações de crédito de um cliente. O consumidor escolhe quais blocos de dados quer (`expand`), quais campos (`fields`) e quais itens (`filter`). Os dados vêm de duas fontes com o mesmo contrato:

- **Visão consolidada** (DynamoDB): rápida, eventualmente consistente. Rota padrão.
- **Fonte primária** (mainframe DB2, via API gateway que devolve trancode): sem defasagem, mais lenta.

## 2. Contrato HTTP

### 2.1 Rotas

| Rota | Fonte | Cache |
| --- | --- | --- |
| `GET /v1/operacoes-credito` | Visão consolidada (DynamoDB) | Sim (Valkey) |
| `GET /v1/operacoes-credito/fonte-primaria` | Fonte primária (mainframe) | Não (ADR-0002) |

Ambas aceitam exatamente os mesmos headers, query params e devolvem o mesmo corpo.

### 2.2 Headers de entrada

| Header | Obrigatório | Regra |
| --- | --- | --- |
| `x-tipo-consulta` | Sim | `OPERACAO`, `CLIENTE` ou `CONTA` |
| `x-correlationid` | Não | Até 64 caracteres `[A-Za-z0-9-]`. Ausente: gerar UUID. Inválido: 400 |
| `x-consumidor` | Não | Injetado pelo API Gateway a partir da API key. Ausente: `desconhecido` |

### 2.3 Query params

| Param | Quando | Regra |
| --- | --- | --- |
| `id_operacao` | `OPERACAO` | Numérico |
| `cpf_cnpj` | `CLIENTE` | CPF (11 dígitos) ou CNPJ (14 caracteres, numérico ou alfanumérico), com DV válido |
| `conta` | `CONTA` | Só dígitos |
| `expand` | Opcional | Lista de blocos separados por vírgula |
| `fields` | Opcional | `bloco.campo` separados por vírgula |
| `filter` | Opcional | Expressão RSQL |
| `limite_operacoes` | Opcional, só `CLIENTE`/`CONTA` | 1 a 500. Ausente: todas as operações |

Header e param incoerentes (ex.: `CONTA` com `id_operacao`, ou `limite_operacoes` em `OPERACAO`) → 400.

### 2.4 Blocos (`expand`)

Nome canônico idêntico no `expand`, na chave do JSON e na SK do DynamoDB:

`operacao`, `originacao`, `componentes`, `parcelas`, `amortizacoes`, `recebimentos`, `plano`, `saldo`, `controles_iof`

- `operacao` e `originacao` vêm **sempre**, com ou sem `expand`.
- Bloco desconhecido → 400 listando os válidos.

### 2.5 Seleção de campos (`fields`)

`fields=operacao.data_contratacao_operacao,parcelas.numero_parcela_amortizacao,parcelas.situacao_parcela`

- Bloco sem campos listados volta completo.
- `id_operacao` volta sempre em todo item de todo bloco. Se o consumidor excluir todos os campos de `operacao`/`originacao`, o bloco volta só com `{"id_operacao": ...}`.
- Campo desconhecido, ou campo de bloco que não está no `expand` → 400 (sem expansão implícita).

### 2.6 Filtro (`filter`)

RSQL (`rsql-parser`), avaliado em memória após a leitura (o `texto_detalhe` é GZIP opaco para o DynamoDB).

`filter=parcelas.numero_parcela_amortizacao=ge=4;parcelas.situacao_parcela==ABERTA`

- O filtro afeta **só o bloco que nomeia**. Filtrar `parcelas` não remove amortizações nem `saldo_parcelas` das parcelas excluídas.
- Exceção: filtro em `operacao` ou `originacao` remove a **operação inteira** de `data` (ex.: `operacao.situacao_operacao_credito==ATIVA` em consulta por cliente).
- Comparações usam o tipo do campo declarado no catálogo (número, decimal, data, string, booleano).
- Filtro em campo desconhecido ou em bloco fora do `expand` → 400.

### 2.7 Resposta

Corpo sempre `{"data": [ ... ]}`, mesmo em `OPERACAO`. Cada elemento é uma operação, com uma chave por bloco pedido. Ver `docs/exemplo_operacao.json`.

| Situação | Status | Detalhe |
| --- | --- | --- |
| Sucesso | 200 | |
| Algum bloco pedido não existe ainda na visão consolidada | 200 | Chave do bloco = `null` + header `x-blocos-indisponiveis: saldo,plano` |
| `OPERACAO` não encontrada | 404 | Problem Details |
| `CLIENTE`/`CONTA` sem operações | 200 | `{"data": []}` |
| Validação de entrada | 400 | Problem Details listando valores válidos |
| Prazo da requisição estourado | 504 | Problem Details sugerindo `limite_operacoes`, menos `expand` e consulta por `OPERACAO` |
| Falha em qualquer operação do fan-out (timeout/5xx da dependência) | 502 ou 504 | A requisição inteira falha; sem resposta parcial |
| Rate limiter interno ou circuit breaker aberto | 503 | `Retry-After` |
| Excesso no Usage Plan | 429 | Devolvido pelo API Gateway, não pela aplicação |

Todos os erros seguem Problem Details (RFC 9457) e incluem o `correlation_id`. A resposta sempre devolve o header `x-correlationid`.

### 2.8 Exemplos

```
GET /v1/operacoes-credito?id_operacao=123456789&expand=parcelas&filter=parcelas.numero_parcela_amortizacao=ge=4
x-tipo-consulta: OPERACAO
```

```
GET /v1/operacoes-credito?cpf_cnpj=12345678909&expand=saldo&limite_operacoes=10&fields=operacao.data_contratacao_operacao,saldo.saldo_devedor_operacao
x-tipo-consulta: CLIENTE
```

## 3. Modelo dos blocos

- Os blocos trafegam como `Map<String, Object>` de ponta a ponta, sem classe Java por campo (ADR-0005).
- Um **catálogo YAML** declara, por bloco, os campos válidos e o tipo de cada um. Ele alimenta a validação de `fields`/`filter` e a tipagem do RSQL. Incluir ou remover campo = editar o catálogo.
- Decimais sempre `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`); datas como string no formato da origem (`yyyy-MM-dd` ou `yyyy-MM-ddTHH:mm:ss`).
- Schemas dos blocos 1 a 8: ver `tabela-dynamodb-expand-operacoes-consignado.md`.
- Bloco 9, `controles_iof` (array, 1..N por parcela):

| Campo | Tipo |
| --- | --- |
| `id_operacao` | número |
| `numero_parcela_amortizacao` | número |
| `numero_plano_amortizacao` | número |
| `numero_sequencial_controle_iof` | número |
| `valor_iof_devido` | decimal |
| `valor_iof_pago` | decimal |
| `data_pagamento_iof` | data, pode ser `null` |

## 4. Visão consolidada (DynamoDB)

Substitui o esquema de chaves de `tabela-dynamodb-expand-operacoes-consignado.md` (ADR-0001).

### 4.1 Tabela base

| Atributo | Tipo | Papel |
| --- | --- | --- |
| `numero_operacao` | N | PK |
| `custodia_expand` | S | SK: `SF#EXPAND#<BLOCO>` (`OPERACAO`, `ORIGINACAO`, `COMPONENTES`, `PARCELAS`, `AMORTIZACOES`, `RECEBIMENTOS`, `PLANO`, `SALDO`, `CONTROLES_IOF`) |
| `codigo_expand` | N | 1 a 9 |
| `texto_detalhe` | B | JSON do bloco, minificado, GZIP |
| `data_insercao` / `data_alteracao` | S | Auditoria (sem acento no nome) |
| `cpf_cnpj_cliente`, `conta_cliente`, `sk_gsi` | S | **Só no item `OPERACAO`** (GSIs esparsos) |

### 4.2 GSIs esparsos (projeção `ALL`)

| GSI | PK | SK |
| --- | --- | --- |
| por cliente | `cpf_cnpj_cliente` | `sk_gsi` = `<data_contratacao_operacao>#<numero_operacao>` |
| por conta | `conta_cliente` | `sk_gsi` (mesmo valor) |

### 4.3 Padrões de acesso

| Consulta | Passo 1 | Passo 2 |
| --- | --- | --- |
| `OPERACAO` | `BatchGetItem` (ou `GetItem`s em paralelo) na tabela base com as chaves `(id, SF#EXPAND#<bloco>)` dos blocos pedidos + `operacao` + `originacao` | — |
| `CLIENTE` / `CONTA` | `Query` no GSI, `ScanIndexForward=false`, `Limit=limite_operacoes`. Já devolve o bloco `operacao` | `BatchGetItem` na tabela base só com `originacao` + blocos pedidos de cada operação (lotes de até 100 chaves, em paralelo) |

O DynamoDB seleciona blocos; filtros sobre campos dentro de um bloco rodam em memória. Tratar `UnprocessedKeys` do `BatchGetItem`.

## 5. Fonte primária (mainframe via WireMock)

### 5.1 Rotas simuladas (todas devolvem trancode em texto)

| Rota | Devolve |
| --- | --- |
| `GET /mainframe/operacoes/{id}/blocos/{bloco}` | Trancode do bloco |
| `GET /mainframe/clientes/{cpf_cnpj}/operacoes` | Lista de `id_operacao` + `data_contratacao_operacao`, mais recente primeiro |
| `GET /mainframe/contas/{conta}/operacoes` | Idem |

Fluxo `CLIENTE`/`CONTA`: chama a lista → aplica `limite_operacoes` → fan-out de uma chamada por operação × bloco pedido (mais `operacao` e `originacao`), em paralelo, com teto de concorrência por requisição.

### 5.2 Trancode

- Texto de posições fixas. Blocos-objeto: um registro. Blocos-lista: prefixo de 3 dígitos com a quantidade + registros de tamanho fixo. Blocos aninhados (`plano`, `saldo`): sub-listas com o mesmo esquema de prefixo.
- Cada bloco tem um layout em `trancode/layouts/<bloco>.yml` com `campo`, `inicio`, `tamanho`, `tipo`, `formato` (data, decimal com casas implícitas, booleano `S`/`N`, string com trim, vazio → `null`). Um parser genérico lê qualquer layout e produz o mesmo `Map` que a visão consolidada produz.
- Mudar o template do trancode = editar o YAML, sem mudar Java.
- Exemplo (bloco `operacao`): `"123456789ATIVA2026-11-21          12458965432"` → `{"id_operacao": 123456789, "situacao_operacao_credito": "ATIVA", "data_contratacao_operacao": "2026-11-21", "data_baixa_operacao": null, "cpf_cnpj": "12458965432"}`.
- Inventar as massas de trancode do WireMock, coerentes com a massa do DynamoDB.

## 6. Cache (Valkey, só visão consolidada)

- Chave `op:{id_operacao}:{bloco}`; valor = `texto_detalhe` GZIP como veio do DynamoDB (sem recomprimir). TTL configurável, default 5 min.
- Por requisição: um `MGET` de todas as chaves `operacao × bloco`; só os ausentes vão ao DynamoDB e são gravados no cache em seguida. Ex.: após `expand=parcelas,amortizacoes`, uma chamada `expand=parcelas,saldo` só busca `saldo` no DynamoDB.
- A lista de ids por cliente/conta **não** é cacheada (a `Query` no GSI é barata e evita esconder operações novas).
- Valkey indisponível → segue direto no DynamoDB (fail-open), com circuit breaker próprio e timeout ~10 ms.

## 7. Resiliência

Resilience4j 2.4+ (`resilience4j-spring-boot4`), todos os valores em `application.yml`.

| Mecanismo | DynamoDB | Fonte primária | Valkey | SQS |
| --- | --- | --- | --- | --- |
| Circuit breaker | Sim | Sim | Sim | Sim |
| Bulkhead (semáforo) | Próprio | Próprio | Próprio | Próprio |
| Rate limiter interno | Sim → 503 | Sim → 503 | — | — |
| Retry | **Só o do SDK** (`standard`, 2 extras, backoff + jitter) | **Só Resilience4j** (2 extras, backoff exponencial base 10 ms + jitter) | Não | SDK |
| Pool de conexões | Cliente HTTP do SDK com pool configurado | Apache HttpClient 5 (máx. por rota, TTL, keep-alive) | Pool do cliente | SDK |

- **Uma camada de retry por dependência**, nunca duas.
- Retry só para erros retentáveis (throttling, 5xx, timeout de conexão, `UnprocessedKeys`), nunca 4xx, e só se o prazo restante da requisição comportar outra tentativa.
- **Prazo por tipo de consulta** (fail fast → 504): `OPERACAO` 150 ms, `CLIENTE`/`CONTA` 400 ms (acima dos P99 alvo, para não derrubar 1% por construção).
- **Bulkhead por tipo de consulta**: `CLIENTE`/`CONTA` têm teto próprio e não sufocam `OPERACAO`.
- **Teto de fan-out por requisição** (ex.: 16 chamadas simultâneas).
- Sem fallback cruzado entre visão consolidada e fonte primária.
- O 429 é do API Gateway (Usage Plan, por consumidor); a aplicação usa 503 para se proteger.

## 8. Execução

- Java 25, Spring Boot 4.x, Maven, módulo único.
- Spring MVC com virtual threads (`spring.threads.virtual.enabled=true`). Sem WebFlux.
- AWS SDK v2 síncrono (DynamoDB, SQS).
- Fonte primária: HTTP Interface (`@HttpExchange`) sobre `RestClient` + Apache HttpClient 5. Sem Feign.
- Fan-out: `Executors.newVirtualThreadPerTaskExecutor()` envolto em propagação de contexto (Micrometer Context Propagation), levando MDC, trace e correlationId às threads filhas. Sem features preview (`StructuredTaskScope`).

## 9. Arquitetura

- Hexagonal: `domain`, `application` (casos de uso + portas), `adapters.in.web`, `adapters.out.dynamodb`, `adapters.out.fonteprimaria`, `adapters.out.cache`, `adapters.out.eventos`.
- ArchUnit garante que `domain` e `application` não dependem de Spring, AWS SDK nem Jackson de adapter.
- As duas rotas usam o **mesmo caso de uso**; muda só a porta de saída injetada (Strategy).
- Evitar cadeias de `if/else`: preferir Strategy, mapas de handlers, `switch` com pattern matching, `sealed`. Seguir as skills `design-patterns`, `java-modern` e `conventions` (sem Lombok, injeção por construtor, `RestClient`, Problem Details).
- Simplicidade e legibilidade acima de abstração especulativa.

## 10. Observabilidade

- **Pipeline**: app → OTLP → OpenTelemetry Collector → Prometheus (métricas) e Jaeger (traces); Grafana sobre ambos. Datadog no futuro = trocar exporter no Collector, sem mudar código.
- **Traces**: Micrometer Tracing + OpenTelemetry, spans por dependência (DynamoDB, Valkey, fonte primária, SQS) e por etapa (consulta GSI, batch, fan-out, filtro). Tail sampling no Collector: 100% de erros e lentos (> P99), ~10% do resto.
- **Métricas** (baixa cardinalidade): tags `consumidor`, `tipo_consulta`, `rota`, `status`, `bloco`. URI sempre como template. Contador `consultas.blocos` por bloco pedido. Nunca `id_operacao`, CPF ou conta em tag.
- **Logs**: JSON via structured logging nativo do Spring Boot (ECS), appender assíncrono, um log de acesso por requisição (consumidor, tipo, blocos, campos, filtro, status, duração, hit/miss por bloco). CPF/CNPJ e conta mascarados em logs e spans.
- **Contexto**: um filtro/interceptor, antes de qualquer processamento, resolve `x-correlationid`, `x-consumidor` e tipo de consulta, coloca no MDC e no span, e os remove ao final. Nada disso no código de negócio.
- **Propagação**: `x-correlationid` + `traceparent` (W3C) repassados à fonte primária.

## 11. Evento de consumo (SQS → Firehose → S3 → Athena)

Uma consulta = um evento de consumo publicado no SQS (ADR-0004). O consumidor do SQS, o Firehose e o Athena ficam fora deste repositório.

- **Best-effort e assíncrono**: fila em memória limitada (ex.: 10.000); virtual thread envia via `SendMessageBatch` (10 por lote, ou a cada 1 s). Fila cheia ou SQS fora → descarta e incrementa `eventos_consumo_descartados`. A requisição nunca espera nem falha por isso. Drenar a fila no shutdown gracioso.
- **Conteúdo** (JSON plano, snake_case): `versao_evento`, `timestamp`, `correlation_id`, `trace_id`, `consumidor`, `rota`, `tipo_consulta`, `id_operacao` (quando houver), `cpf_cnpj_hmac`, `conta_hmac`, `blocos_solicitados[]`, `campos_solicitados[]`, `filtro`, `limite_operacoes`, `quantidade_operacoes_retornadas`, `blocos_indisponiveis[]`, `cache_hits`/`cache_misses` por bloco, `status_http`, `codigo_erro`, `duracao_ms`.
- CPF/CNPJ e conta só como HMAC-SHA256 com segredo da aplicação.
- Schema aditivo; mudança incompatível incrementa `versao_evento`. Documentar em `docs/eventos/evento-consumo.md`.
- Métricas servem para operar a API; o evento de consumo responde perguntas de negócio (campos e filtros mais usados, consumo por consumidor ao longo do tempo).

## 12. Ambiente local (docker-compose)

- **LocalStack Community, tag fixada** (sem auth token): DynamoDB (tabela + GSIs da seção 4) e SQS (ADR-0003).
- Script de init: cria tabela, GSIs e fila; carrega a massa.
- **Valkey**, **WireMock** (fonte primária), **OpenTelemetry Collector**, **Prometheus**, **Jaeger**, **Grafana** (com dashboards provisionados).
- **API Gateway não sobe localmente**: Usage Plan, API key, 429 e injeção de `x-consumidor` ficam em IaC e são validados na AWS real (ADR-0003). Localmente, o `x-consumidor` é enviado na própria requisição.
- **Massa de dados**: gerar uma massa nova e coerente (ids, CPF/CNPJ, conta e saldos que fecham com parcelas e amortizações), com: cliente com várias operações, conta com várias operações, operação liquidada, operação com amortização parcial, operação sem algum bloco (para o `x-blocos-indisponiveis`), cliente CNPJ alfanumérico. A massa de `exemplo_dados_dynamodb.csv` tem ids, CPF e saldos inconsistentes e não deve ser reaproveitada como está.

## 13. Requisitos não funcionais

| Consulta | P99 alvo | Prazo (504) |
| --- | --- | --- |
| `OPERACAO` | 80 ms | 150 ms |
| `CLIENTE` | 200 ms | 400 ms |
| `CONTA` | 200 ms | 400 ms |

Sem paginação nesta versão; `limite_operacoes` é a ferramenta do consumidor para controlar latência.

## 14. Segurança

- v1: apenas API key no API Gateway; consumidores são sistemas internos confiáveis. OAuth2 por escopo fica como evolução.
- Dados pessoais (CPF/CNPJ, conta) nunca em claro em logs, spans, métricas ou eventos.

## 15. Fora do escopo

- Paginação.
- Resposta parcial em falha de fan-out.
- Fallback entre visão consolidada e fonte primária.
- Carga do DB2 para o DynamoDB.
- Consumidor do SQS, Firehose, S3 e Athena.
- Autorização por cliente (OAuth2).
- Uso de SQS para invalidação de cache.
