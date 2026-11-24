# consultas-operacoes-credito

API REST, somente leitura, de consulta de operações de crédito da Custódia de Ativos PF.
Descrição completa em `docs/prompt_detalhes_2.md`; vocabulário em `GLOSSARY.md`; decisões em `docs/adr/`.

**Versão 1.1.0** — consulta `OPERACAO` na visão consolidada (DynamoDB) com qualquer combinação dos
9 blocos via `expand`, catálogo de campos por bloco e massa local completa e coerente.

| Versão | Mudança |
| --- | --- |
| 1.1.0 | `expand` com os 9 blocos (inclui `controles_iof`), `x-blocos-indisponiveis`, catálogo de campos, massa nova |
| 1.0.0 | Esqueleto do projeto e consulta `OPERACAO` com os blocos `operacao` e `originacao` |

## Stack

Java 25, Spring Boot 4.2 (Spring MVC com virtual threads), AWS SDK v2 síncrono, Maven (com wrapper).
Organização hexagonal em `br.com.consultas.operacoescredito`:

| Pacote | Conteúdo |
| --- | --- |
| `domain` | `Bloco`, `TipoConsulta`, `CriterioConsulta`, `OperacaoConsultada`, o catálogo de campos em `domain.catalogo` e a seleção de campos em `domain.selecao` (sem framework) |
| `application` | Caso de uso `ConsultarOperacoesUseCase` e porta de saída `FonteDeOperacoes` (sem framework) |
| `adapters.in.web` | Controller, validação dos parâmetros, Problem Details |
| `adapters.out.dynamodb` | Visão consolidada (ADR-0001) |
| `config` | Ligação dos casos de uso às portas; leitura do catálogo de campos |

As regras de dependência são verificadas pelo ArchUnit (`ArquiteturaTest`).

## Contrato disponível nesta versão

```
GET /v1/operacoes-credito?id_operacao=100000003&expand=parcelas,saldo,plano
x-tipo-consulta: OPERACAO
```

```
HTTP/1.1 200
x-blocos-indisponiveis: plano,saldo

{"data": [{"operacao": {...}, "originacao": {...}, "parcelas": [...], "plano": null, "saldo": null}]}
```

- `expand`: blocos separados por vírgula, com o nome canônico (o mesmo da chave no JSON e do sufixo da SK):
  `operacao`, `originacao`, `componentes`, `parcelas`, `amortizacoes`, `recebimentos`, `plano`, `saldo`,
  `controles_iof`. `operacao` e `originacao` vêm sempre; repetições e espaços em volta dos nomes são ignorados.
- Só os blocos pedidos são lidos do DynamoDB (um `BatchGetItem` com uma chave por bloco).
- Bloco pedido e ainda ausente na visão consolidada: chave com `null` e o bloco listado em
  `x-blocos-indisponiveis` (ordem canônica). Blocos-lista vazios (`[]`) não são indisponíveis.
- `fields`: `bloco.campo` separados por vírgula, com ponto também para campos aninhados
  (ex.: `fields=operacao.situacao_operacao_credito,saldo.saldo_parcelas.saldo_devedor`). Bloco sem campos
  listados volta completo; `id_operacao` volta sempre, em todo item de todo nível onde existe (`fields=operacao.id_operacao`
  reduz `operacao` a `{"id_operacao": ...}`); campo composto listado inteiro volta completo. A seleção é aplicada
  pelo caso de uso ao que a fonte devolveu, então vale para qualquer rota e tipo de consulta.
- `filter`: RSQL avaliado em memória sobre o que a fonte devolveu, com seletor `<bloco>.<campo>` (caminho com
  ponto em campos aninhados) e comparação pelo tipo do catálogo. Operadores `==`, `!=`, `=lt=`/`<`, `=le=`/`<=`,
  `=gt=`/`>`, `=ge=`/`>=`, `=in=(a,b)`, `=out=(a,b)`; `;` é "e", `,` é "ou", parênteses agrupam. Ex.:
  `filter=parcelas.numero_parcela_amortizacao=ge=4;parcelas.situacao_parcela==ABERTA`.
  - Cada condição filtra só a sua lista: os itens do bloco-lista ou, em bloco-objeto, a primeira lista do
    caminho (`saldo.saldo_parcelas.saldo_devedor=gt=0`). Listas mais internas valem se algum item atende.
  - Em `operacao`/`originacao` a operação que não atende sai de `data` (consulta por operação: `{"data": []}`).
  - Campo nulo ou ausente só atende a `!=` e `=out=`; texto e booleano aceitam só `==`, `!=`, `=in=`, `=out=`.
  - 400: sintaxe inválida, bloco ou campo fora do catálogo, bloco fora do `expand`, campo composto, argumento
    fora do tipo, campo de primeiro nível de bloco-objeto que não seja `operacao`/`originacao`, ou `,` (ou)
    misturando listas diferentes.

| Situação | Status |
| --- | --- |
| Operação encontrada | 200 |
| Algum bloco pedido ausente na visão consolidada | 200 + `x-blocos-indisponiveis` |
| Operação inexistente | 404 (Problem Details) |
| `x-tipo-consulta` ausente/inválido, `id_operacao` ausente/não numérico, parâmetro incoerente com o tipo (`cpf_cnpj`, `conta` ou `limite_operacoes` em `OPERACAO`; `id_operacao` em `CLIENTE`/`CONTA`) | 400 (Problem Details) |
| Bloco desconhecido no `expand` (inclusive maiúsculas ou item vazio) | 400 (Problem Details listando os blocos válidos) |
| `fields` malformado, com bloco desconhecido, bloco fora do `expand` (sem expansão implícita) ou campo fora do catálogo | 400 (Problem Details listando blocos ou campos válidos) |
| `CLIENTE`/`CONTA` sem operações | 200 com `{"data": []}` |
| `cpf_cnpj` que não é CPF (11 dígitos) ou CNPJ (14 caracteres, alfanumérico em maiúsculas) com DV válido; `conta` com caractere não numérico; `limite_operacoes` fora de 1 a 500 | 400 (Problem Details) |

`CLIENTE` (`cpf_cnpj`) e `CONTA` (`conta`) devolvem as operações da mais recente para a mais antiga por data
de contratação, até `limite_operacoes` (ausente: todas). A leitura segue o ADR-0001: `Query` no GSI esparso
(`ScanIndexForward=false`, `Limit` = limite, paginada), que já traz o bloco `operacao`, e `BatchGetItem` de
`originacao` + blocos pedidos em lotes de até 100 chaves, em paralelo (uma virtual thread por lote, com o MDC
propagado), relendo `UnprocessedKeys` com backoff.

## Fonte primária

`GET /v1/operacoes-credito/fonte-primaria` aceita os mesmos headers e parâmetros e devolve o mesmo corpo da
visão consolidada; muda só a porta de saída que o caso de uso recebe (Strategy). Sem cache (ADR-0002).
Atende `OPERACAO`, `CLIENTE` e `CONTA`, com a mesma validação de entrada da visão consolidada (400 antes de
qualquer chamada ao gateway).

| Rota do gateway (trancode em texto) | Devolve |
| --- | --- |
| `GET /mainframe/operacoes/{id}/blocos/{bloco}` | Trancode do bloco |
| `GET /mainframe/clientes/{cpf_cnpj}/operacoes` | Lista de `id_operacao` + `data_contratacao_operacao`, mais recente primeiro (layout `lista_operacoes.yml`) |
| `GET /mainframe/contas/{conta}/operacoes` | Idem |

- Gateway do mainframe via `@HttpExchange` (`GatewayMainframeClient`) sobre `RestClient` + Apache HttpClient 5
  com pool configurável e sem retry próprio.
- `OPERACAO`: uma chamada por bloco pedido (mais `operacao` e `originacao`).
- `CLIENTE`/`CONTA`: uma chamada à lista do cliente ou da conta → ordenação da mais recente para a mais antiga
  por data de contratação (no empate, maior `id_operacao` primeiro, como o `sk_gsi` da visão consolidada) →
  `limite_operacoes` aplicado → fan-out de uma chamada por operação selecionada × bloco. Só as N operações
  selecionadas são detalhadas. Lista com 404 no gateway: cliente/conta sem operações (`{"data": []}`).
- O fan-out roda em virtual threads (`Executors.newVirtualThreadPerTaskExecutor()` envolto por
  `ContextExecutorService`, sem features preview), com MDC, trace e correlationId propagados às threads filhas
  e no máximo `consultas.fonte-primaria.fan-out.max-chamadas-simultaneas` (default 16) chamadas em andamento
  por requisição; as demais esperam vaga.
- 404 de um bloco: bloco indisponível (`null` + `x-blocos-indisponiveis`); sem o bloco `operacao`, a operação
  não existe (`OPERACAO`: 404 da API; `CLIENTE`/`CONTA`: a operação listada fica fora de `data`).
- Qualquer chamada que falhar derruba a requisição inteira, sem resposta parcial, e interrompe as chamadas
  em andamento: prazo de conexão, de pool, de resposta ou da requisição esgotado → **504** (Problem Details
  sugerindo `limite_operacoes`, menos blocos no `expand` ou a consulta por `OPERACAO`); 5xx (depois do
  retry), outro 4xx ou trancode fora do layout → **502**; circuit breaker aberto, bulkhead cheio ou rate
  limiter sem permissão → **503** com `Retry-After`, sem chamar o gateway.
- `x-correlationid` é repassado em toda chamada ao gateway; o `traceparent` (W3C) é propagado pela
  observação do `RestClient`.

### Resiliência da fonte primária

Proteção própria (instâncias `fontePrimaria` do Resilience4j, isoladas da visão consolidada), aplicada a cada
chamada ao gateway, de fora para dentro: retry → circuit breaker → rate limiter → bulkhead. Números em
`resilience4j.*.instances.fontePrimaria` no `application.yml`; a classificação das falhas fica em
`FalhasDoGateway`.

| Mecanismo | Comportamento | Resposta |
| --- | --- | --- |
| Prazo da requisição | `consultas.prazo.*` por tipo de consulta (OPERACAO 150 ms, CLIENTE/CONTA 400 ms), contado desde a entrada da requisição. Limita a espera por vaga no fan-out, por conexão do pool e pela resposta de cada tentativa, e o fan-out como um todo (ADR-0006) | 504 com as sugestões |
| Retry | Só o do Resilience4j (o Apache HttpClient não retenta): 2 tentativas extras, backoff exponencial de 10 ms (×2) com jitter de 50%. Só para 5xx e falha/timeout ao **abrir** a conexão; nunca 4xx, timeout de resposta, timeout do pool, circuito aberto, bulkhead ou rate limiter. Só se ainda restar `consultas.fonte-primaria.retry.prazo-minimo-restante` (50 ms) do prazo | a falha da última tentativa (502/504) |
| Circuit breaker | Janela de 50 chamadas, mínimo 20, abre com 50% de falhas (5xx e falhas de I/O, inclusive timeouts; 4xx e 404 contam como sucesso), 5 s aberto, 5 chamadas no meio-aberto. Chamadas interrompidas pelo próprio fan-out não contam | 503 + `Retry-After` (tempo aberto) |
| Bulkhead (semáforo) | 50 chamadas simultâneas ao gateway (= pool), espera de até 10 ms por vaga | 503 + `Retry-After: 1` |
| Rate limiter | 2000 chamadas ao gateway por segundo, sem fila | 503 + `Retry-After` (período do limite) |

Cada tentativa tem span e timer `consultas.dependencia` próprios. O Resilience4j publica as métricas
`resilience4j.circuitbreaker.*`, `resilience4j.retry.calls`, `resilience4j.bulkhead.*` e
`resilience4j.ratelimiter.*` com as tags `name` (`fontePrimaria`), `kind` e `state`, sem identificadores.
Lentidão ou falha do mainframe não afeta a visão consolidada: pool, bulkhead, circuito e rate limiter são só
da fonte primária, e cada requisição roda na sua virtual thread.
- Cada trancode é lido por um parser genérico dirigido por `src/main/resources/trancode/layouts/<bloco>.yml`
  (validados na subida). Mudar o template = editar o YAML:

```yaml
bloco: saldo
estrutura: objeto            # objeto: um registro | lista: prefixo de 3 dígitos com a quantidade + registros
registro:
  campos:                    # posições fixas; inicio a partir de 1, relativo ao registro
    - { campo: id_operacao, inicio: 1, tamanho: 18, tipo: numero }
    - { campo: saldo_devedor_operacao, inicio: 19, tamanho: 15, tipo: decimal, formato: 2 }
    - { campo: data_posicao_calculo, inicio: 39, tamanho: 10, tipo: data, formato: yyyy-MM-dd }
  listas:                    # sub-listas após a parte fixa, na ordem declarada, com o mesmo prefixo
    - campo: saldo_parcelas
      registro: { campos: [ ... ] }
```

| `tipo` | `formato` | Conversão |
| --- | --- | --- |
| `numero` | — | inteiro com zeros à esquerda |
| `decimal` | casas implícitas (ex.: `2`) | `BigDecimal` com essa escala |
| `data` / `data_hora` | padrão do `DateTimeFormatter` | string `yyyy-MM-dd` / `yyyy-MM-ddTHH:mm:ss` |
| `booleano` | caracteres de verdadeiro e falso (default `SN`) | `true`/`false` |
| `texto` | — | string sem os brancos das pontas |

Campo todo em branco vira `null`.

## Catálogo de campos

`src/main/resources/catalogo-campos.yml` (importado pelo `application.yml`) declara, para cada bloco, a
estrutura (`objeto` ou `lista`) e os campos com nome, tipo e `anulavel` (ADR-0005). Tipos: `numero`,
`decimal`, `data` (`yyyy-MM-dd`), `data_hora` (`yyyy-MM-ddTHH:mm:ss`), `texto`, `booleano`, e os compostos
`objeto`/`lista`, que declaram os próprios campos (ex.: `plano.dados_plano_amortizacao.encargos_moratorios`,
`saldo.saldo_parcelas`). Os blocos continuam trafegando como `Map`, com decimais em `BigDecimal`.

O catálogo é validado na subida (um bloco por `Bloco`, `id_operacao` numérico em todo bloco, compostos com
subcampos, nomes únicos) e valida o `fields` e valida e tipa o `filter`. Incluir ou remover campo = editar o YAML;
o teste `CatalogoDeCamposMassaTest` garante que a massa local usa exatamente os campos e tipos do catálogo.

## Contexto da requisição e logs

Um filtro (`ContextoRequisicaoFilter`) roda antes de qualquer processamento em toda rota fora de `/actuator`:

| Header | Regra |
| --- | --- |
| `x-correlationid` | Até 64 caracteres `[A-Za-z0-9-]`. Ausente: UUID gerado. Inválido: 400 (com um UUID novo). Sempre devolvido na resposta |
| `x-consumidor` | Ausente: `desconhecido` |

- `correlation_id`, `consumidor`, `tipo_consulta` (ou `invalido`) e `rota` vão para o MDC e são removidos ao final.
  Todo Problem Details traz a propriedade `correlation_id`.
- Logs em JSON (structured logging ECS do Spring Boot) por um `AsyncAppender` (`logback-spring.xml`).
  Perfil `human-logs` para texto legível em desenvolvimento.
- Um log de acesso por requisição (logger `acesso`): `metodo`, `uri`, `blocos`, `status`, `duracao_ms`,
  parâmetros informados (`id_operacao`, `cpf_cnpj`, `conta`, `limite_operacoes`, `fields`, `filter`) e o MDC.
- CPF/CNPJ e conta saem sempre mascarados (`529******25`, `***********5874`) em todo log: campos com esses
  nomes, segmentos de rota `/clientes/<cpf_cnpj>` e `/contas/<conta>` (URLs do gateway do mainframe), pares
  `chave=valor`/RSQL/JSON e CPF/CNPJ soltos com DV válido (`MascaramentoLogCustomizer`).
- O MDC chega às threads filhas por Micrometer Context Propagation (`MdcThreadLocalAccessor`): envolver a
  tarefa com `ContextSnapshotFactory.captureAll().wrap(...)` ou o executor com `ContextExecutorService.wrap(...)`.

## Métricas e traces

Micrometer + OpenTelemetry (`spring-boot-starter-opentelemetry`): a aplicação exporta métricas
(`OtlpMeterRegistry`) e traces (bridge Micrometer Tracing → OpenTelemetry SDK) por OTLP/HTTP para o
OpenTelemetry Collector, que manda métricas ao Prometheus e traces ao Jaeger. Nada de fornecedor no código:
trocar para Datadog é trocar o exporter em `observabilidade/otel-collector.yaml`.

| Métrica | Tags |
| --- | --- |
| `http.server.requests` (timer) | `consumidor`, `tipo_consulta` (`nenhum`/`invalido` quando ausente/inválido), `rota`, `status`, `uri` (template), `method`, `outcome`, `exception` |
| `consultas.blocos` (contador, um incremento por bloco pedido) | `bloco`, `consumidor`, `tipo_consulta`, `rota`, `status` |
| `consultas.dependencia` (timer, um por chamada externa) | `dependencia` (`dynamodb`, `fonte_primaria`), `operacao` (`BatchGetItem`, `Query`... no DynamoDB; o bloco, `lista_operacoes_cliente` ou `lista_operacoes_conta` na fonte primária), `indice` (GSI ou `nenhum`, só DynamoDB) |
| `consultas.etapa` (timer, um por etapa interna) | `etapa` (`consulta_gsi`, `batch`, `lista_operacoes`, `fan_out`, `filtro`); `indice` em `consulta_gsi` |

- `x-consumidor` vira tag como veio (id da API key no gateway); fora de `[A-Za-z0-9._-]{1,64}` vira `invalido`.
  Bloco fora do catálogo não vira tag. `id_operacao`, CPF/CNPJ e conta nunca são tag de métrica.
- Spans: um por requisição HTTP (com `consumidor`, `tipo_consulta`, `rota`, `correlation_id` e os
  identificadores da consulta), com filhos por etapa e por chamada externa. `traceparent` (W3C) recebido
  continua o trace e segue para o gateway da fonte primária.
  - OPERACAO na visão consolidada: `dynamodb BatchGetItem` direto sob o span HTTP.
  - CLIENTE/CONTA: `consulta_gsi` (com os `dynamodb Query`) e `batch` (com os `dynamodb BatchGetItem` dos lotes).
  - Fonte primária: `fan_out` (atributo `operacoes`) com um `fonte_primaria <bloco>` por operação × bloco
    pedido; 404 (bloco inexistente) não é erro. Em `CLIENTE`/`CONTA`, antes dele, a etapa `lista_operacoes`
    com a chamada `fonte_primaria lista_operacoes_cliente` (ou `_conta`).
  - `filtro`, quando há `filter`.
- Novos adapters (Valkey, SQS) e etapas usam `Observacoes.dependencia(...)` e `Observacoes.etapa(...)`
  (`config.observabilidade`), que fixam nomes e tags. A camada de aplicação mede etapas pela porta
  `ObservadorDeEtapas`, sem depender de Micrometer.
- CPF/CNPJ e conta saem mascarados em todo atributo de span (`MascaramentoSpanFilter`, no exportador).
- Buckets de histograma nos prazos do requisito não funcional (`management.metrics.distribution.slo`).
- A aplicação amostra 100%; o **tail sampling** do Collector guarda todo trace com erro, com status 5xx ou
  lento (OPERACAO > 80 ms, CLIENTE/CONTA > 200 ms) e 10% do resto.
- `/actuator/metrics` exposto para inspeção.

## Subir o ambiente local

Pré-requisito: Docker.

```bash
docker compose up -d --build --wait
curl -H "x-tipo-consulta: OPERACAO" "http://localhost:8080/v1/operacoes-credito?id_operacao=100000001"
docker compose down -v
```

O `docker-compose.yml` sobe:

- **LocalStack Community `4.14.0`**, a última tag que roda sem auth token (ADR-0003). Uso exclusivo em
  desenvolvimento local: a tag não recebe patches de segurança.
- **WireMock `3.13.2`** (porta 8089), simulando o gateway do mainframe com os stubs de `wiremock/mappings`.
- **app**, construída pelo `Dockerfile`.
- **OpenTelemetry Collector** `otel/opentelemetry-collector-contrib:0.165.0` (OTLP em `4317`/`4318`).
- **Jaeger v2** `jaegertracing/jaeger:2.23.0`: traces em http://localhost:16686.
- **Prometheus** `prom/prometheus:v3.16.0`: http://localhost:9090.
- **Grafana** `grafana/grafana:13.3.1`: http://localhost:3000 (anônimo, somente leitura; `admin`/`admin`
  para editar). Datasources Prometheus e Jaeger e o dashboard *Consultas de Operações de Crédito*
  (latência por tipo de consulta e rota, latência por dependência, erros, blocos mais pedidos, consumo
  por consumidor) são provisionados de `observabilidade/grafana/`.

O script `localstack/init/ready.d/01-visao-consolidada.sh` cria a tabela `operacoes-credito`
(PK `numero_operacao`, SK `custodia_expand`), os GSIs esparsos `gsi-cliente` e `gsi-conta`
(SK `sk_gsi` = `<data_contratacao_operacao>#<numero_operacao>`, projeção `ALL`) e carrega a massa de
`localstack/massa/<numero_operacao>/` (um `<BLOCO>.json` por bloco, gravado em GZIP no `texto_detalhe`,
e `indices.env` com os atributos dos GSIs, que vão só no item `OPERACAO`).

A massa é gerada por `localstack/gerar_massa.py` a partir de uma especificação curta por operação, para que
os blocos fechem entre si (regras no cabeçalho do script: amortizações aplicadas juros antes do principal,
saldo = principal + juros - amortizado, IOF dos componentes = soma dos controles de IOF etc.). Para incluir
uma operação, acrescente uma entrada em `OPERACOES` e rode `python localstack/gerar_massa.py`. Ao final ele
chama `localstack/gerar_trancode.py` (requer PyYAML), que codifica cada `<BLOCO>.json` com o layout de
trancode do bloco e grava `wiremock/mappings/<id_operacao>.json`, além de `clientes.json` e `contas.json` com a
lista de operações de cada cliente e conta da massa (de `indices.env`): as duas fontes servem a mesma massa.

| `id_operacao` | Situação | CPF/CNPJ | Conta | Cenário |
| --- | --- | --- | --- | --- |
| 100000001 | ATIVA | 52998224725 | 434115000065874 | 12 parcelas, 5 pagas, parcela 6 com amortização parcial e 2 dias de atraso |
| 100000002 | LIQUIDADA | 52998224725 | 434115000065874 | antecipação liquidando 3 parcelas (`BAIXA_INTEGRAL_MAIS_UMA_PARCELA`) |
| 100000003 | ATIVA | 52998224725 | 434115000099991 | sem recebimentos; **sem os blocos `plano` e `saldo`** |
| 100000004 | ATIVA | 12ABC34501DE35 (CNPJ alfanumérico) | 434115000077712 | 10 parcelas, 3 pagas |
| 100000005 | LIQUIDADA | 12ABC34501DE35 | 434115000077712 | 4 parcelas pagas no vencimento |
| 100000006 | ATIVA | 11144477735 | 434115000088823 | 8 parcelas, 2 pagas |
| 100000101 a 100000114 | ATIVA | 11222333000181 | 434115000055501 | 14 operações mensais (2025-09-03 a 2026-10-03), 2 parcelas, sem recebimentos |

O cliente 52998224725 tem três operações em duas contas; a conta 434115000065874 e a 434115000077712 têm
duas operações cada. O cliente 11222333000181 (e a conta 434115000055501) tem 14 operações: com todos os
blocos no `expand`, o `BatchGetItem` passa de 100 chaves e é dividido em lotes.

Para rodar a aplicação fora do Docker contra o LocalStack do compose:

```bash
docker compose up -d --wait localstack
CONSULTAS_DYNAMODB_ENDPOINT=http://localhost:4566 AWS_REGION=sa-east-1 \
CONSULTAS_DYNAMODB_CREDENCIAIS_ACCESS_KEY_ID=test CONSULTAS_DYNAMODB_CREDENCIAIS_SECRET_ACCESS_KEY=test \
./mvnw spring-boot:run
```

## Configuração

| Propriedade | Variável | Default |
| --- | --- | --- |
| `consultas.dynamodb.tabela` | `CONSULTAS_DYNAMODB_TABELA` | `operacoes-credito` |
| `consultas.dynamodb.regiao` | `AWS_REGION` | `sa-east-1` |
| `consultas.dynamodb.endpoint` | `CONSULTAS_DYNAMODB_ENDPOINT` | vazio (endpoint da AWS) |
| `consultas.dynamodb.credenciais.*` | `CONSULTAS_DYNAMODB_CREDENCIAIS_ACCESS_KEY_ID` / `..._SECRET_ACCESS_KEY` | vazio (cadeia padrão do SDK) |
| `consultas.fonte-primaria.url` | `CONSULTAS_FONTE_PRIMARIA_URL` | `http://localhost:8089` |
| `consultas.fonte-primaria.layouts` | — | `classpath:trancode/layouts/` |
| `consultas.fonte-primaria.pool.*` | — | `max-conexoes` 50, `max-conexoes-por-rota` 50, `ttl` 5m, `keep-alive` 30s, `timeout-conexao` 200ms, `timeout-obter-conexao` 50ms, `timeout-resposta` 1s |
| `consultas.fonte-primaria.fan-out.max-chamadas-simultaneas` | `CONSULTAS_FONTE_PRIMARIA_MAX_CHAMADAS_SIMULTANEAS` | `16` (teto de chamadas ao gateway em andamento por requisição; mínimo 1) |
| `consultas.fonte-primaria.retry.prazo-minimo-restante` | — | `50ms` (só retenta se restar isto do prazo da requisição) |
| `consultas.prazo.operacao` / `.cliente` / `.conta` | `CONSULTAS_PRAZO_OPERACAO` / `_CLIENTE` / `_CONTA` | `150ms` / `400ms` / `400ms` (prazo da requisição → 504) |
| `resilience4j.circuitbreaker.instances.fontePrimaria.*` | — | janela `COUNT_BASED` de 50, mínimo 20 chamadas, 50% de falhas, 5s aberto, 5 chamadas no meio-aberto |
| `resilience4j.bulkhead.instances.fontePrimaria.*` | — | `max-concurrent-calls` 50, `max-wait-duration` 10ms |
| `resilience4j.ratelimiter.instances.fontePrimaria.*` | — | `limit-for-period` 2000 por `1s`, `timeout-duration` 0s |
| `resilience4j.retry.instances.fontePrimaria.*` | — | `max-attempts` 3, `wait-duration` 10ms, backoff exponencial ×2, jitter 0.5 |
| Endpoint OTLP (traces e métricas) | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` (só métricas; traces só com a variável) |
| `management.opentelemetry.resource-attributes.deployment.environment.name` | `CONSULTAS_AMBIENTE` | `local` |

## Testes

```bash
./mvnw verify
```

Requer JDK 25 e Docker. `verify` roda:

- testes unitários e ArchUnit (Surefire);
- testes HTTP de caixa-preta `*IT` (Failsafe), com a aplicação inteira e o LocalStack via Testcontainers,
  usando a mesma imagem, o mesmo script de init e a mesma massa do docker-compose, e o WireMock com os
  mesmos stubs de trancode (`FontePrimariaIT` e `FontePrimariaClienteContaIT` comparam as duas rotas para
  cada operação, cliente e conta da massa; `FontePrimariaFanOutIT` registra massas próprias no WireMock para
  teto de concorrência, falhas parciais e correlationId nas chamadas filhas; `ResilienciaFontePrimariaIT` e
  `RateLimiterFontePrimariaIT` usam atrasos, falhas e cenários do WireMock para retry, prazo (504), circuit
  breaker abrindo e voltando, bulkhead e rate limiter (503); `IsolamentoFontePrimariaIT` mostra a visão
  consolidada saudável com o mainframe degradado).
- Nos testes, os prazos por tipo de consulta são de 10 s e o circuito da fonte primária só abre com 1000
  chamadas (`src/test/resources/application.properties`), para que containers frios e falhas provocadas de
  propósito não afetem outros testes; os testes de resiliência usam contexto próprio com valores pequenos.

O CI (`.github/workflows/ci.yml`) executa `./mvnw -B verify` e constrói a imagem Docker.
