# consultas-operacoes-credito

API REST, somente leitura, de consulta de operações de crédito da Custódia de Ativos PF.
Descrição completa em `docs/prompt_detalhes_2.md`; vocabulário em `GLOSSARY.md`; decisões em `docs/adr/`.

**Versão 1.3.0** — consultas `OPERACAO`, `CLIENTE` e `CONTA` na visão consolidada (DynamoDB, com cache) e na
fonte primária, com `expand`, `fields` e `filter`, e um evento de consumo por requisição publicado no SQS.

| Versão | Mudança |
| --- | --- |
| 1.3.0 | Correções da revisão: parâmetro repetido e `conta` com mais de 20 dígitos viram 400; circuitos não contam falhas causadas pela própria API (prazo, pool, 4xx); máscara de dados pessoais nos spans; cache em comandos pequenos; ADRs 0009–0011 |
| 1.2.0 | Evento de consumo no SQS (best-effort, em lote, HMAC de CPF/CNPJ e conta); contrato em `docs/eventos/evento-consumo.md` |
| 1.1.0 | `expand` com os 9 blocos (inclui `controles_iof`), `x-blocos-indisponiveis`, catálogo de campos, massa nova |
| 1.0.0 | Esqueleto do projeto e consulta `OPERACAO` com os blocos `operacao` e `originacao` |

## Stack

Java 25, Spring Boot 4.2 (Spring MVC com virtual threads), AWS SDK v2 síncrono, Maven (com wrapper).
Organização hexagonal em `br.com.consultas.operacoescredito`:

| Pacote | Conteúdo |
| --- | --- |
| `domain` | `Bloco`, `TipoConsulta`, `CriterioConsulta`, `OperacaoConsultada`, o catálogo de campos em `domain.catalogo` e a seleção de campos em `domain.selecao` (sem framework) |
| `application` | Casos de uso `ConsultarOperacoesUseCase` e `RegistrarConsumoUseCase`, portas de saída `FonteDeOperacoes` e `PublicadorDeEventosDeConsumo` (sem framework) |
| `adapters.in.web` | Controller, validação dos parâmetros, Problem Details, contexto da requisição e evento de consumo |
| `adapters.out.dynamodb` | Visão consolidada (ADR-0001) |
| `adapters.out.cache` | Cache por bloco da visão consolidada no Valkey (ADR-0002) |
| `adapters.out.eventos` | Publicação do evento de consumo no SQS (ADR-0004) |
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
- Cada parâmetro do contrato (`id_operacao`, `cpf_cnpj`, `conta`, `limite_operacoes`, `expand`, `fields`,
  `filter`) vai no máximo uma vez: listas vão separadas por vírgula no mesmo parâmetro. Repetido
  (`expand=parcelas&expand=saldo`, `filter=a&filter=b`) é 400, para que dois filtros não virem um "ou" sem aviso.
- Só os blocos pedidos e fora do cache são lidos do DynamoDB (um `BatchGetItem` com uma chave por bloco).
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
    Os dois blocos filtram o mesmo alvo (a operação), então podem ser ligados por `,` (ou):
    `operacao.situacao_operacao_credito==ATIVA,originacao.canal_contratacao==MOBILE`.
  - Campo nulo ou ausente só atende a `!=` e `=out=`; texto e booleano aceitam só `==`, `!=`, `=in=`, `=out=`.
  - 400: sintaxe inválida, bloco ou campo fora do catálogo, bloco fora do `expand`, campo composto, argumento
    fora do tipo, campo de primeiro nível de bloco-objeto que não seja `operacao`/`originacao`, ou `,` (ou)
    misturando listas diferentes.

| Situação | Status |
| --- | --- |
| Operação encontrada | 200 |
| Algum bloco pedido ausente na visão consolidada | 200 + `x-blocos-indisponiveis` |
| Operação inexistente | 404 (Problem Details) |
| `x-tipo-consulta` ausente/inválido (o detalhe lista os valores válidos, sem ecoar o recebido), `id_operacao` ausente/não numérico/com mais de 18 dígitos, parâmetro incoerente com o tipo (`cpf_cnpj`, `conta` ou `limite_operacoes` em `OPERACAO`; `id_operacao` em `CLIENTE`/`CONTA`), parâmetro do contrato repetido | 400 (Problem Details) |
| Bloco desconhecido no `expand` (inclusive maiúsculas ou item vazio) | 400 (Problem Details listando os blocos válidos) |
| `fields` malformado, com bloco desconhecido, bloco fora do `expand` (sem expansão implícita) ou campo fora do catálogo | 400 (Problem Details listando blocos ou campos válidos) |
| `CLIENTE`/`CONTA` sem operações | 200 com `{"data": []}` |
| `cpf_cnpj` que não é CPF (11 dígitos) ou CNPJ (14 caracteres, alfanumérico em maiúsculas) com DV válido; `conta` com caractere não numérico ou mais de 20 dígitos (barrada antes de virar chave no DynamoDB); `limite_operacoes` fora de 1 a 500 | 400 (Problem Details) |
| Prazo da requisição do tipo de consulta esgotado (DynamoDB lento) | 504 (Problem Details sugerindo `limite_operacoes`, menos blocos no `expand` e a consulta por `OPERACAO`) |
| Falha do DynamoDB depois do retry do SDK, ou item fora do formato (atributo ausente, `texto_detalhe` corrompido) | 502 (Problem Details) |
| Circuit breaker do DynamoDB aberto, bulkhead cheio (total ou de CLIENTE/CONTA), rate limiter interno sem permissão, pool de conexões esgotado, throttling do DynamoDB depois do retry ou `UnprocessedKeys` restantes após 3 leituras | 503 (Problem Details) + `Retry-After` em segundos |

`CLIENTE` (`cpf_cnpj`) e `CONTA` (`conta`) devolvem as operações da mais recente para a mais antiga por data
de contratação, até `limite_operacoes` (ausente: todas). A leitura segue o ADR-0001: `Query` no GSI esparso
(`ScanIndexForward=false`, `Limit` = limite, paginada), que já traz o bloco `operacao`, e `BatchGetItem` de
`originacao` + blocos pedidos em lotes de até 100 chaves, em paralelo (uma virtual thread por lote, com o MDC
propagado, no máximo `consultas.dynamodb.max-lotes-simultaneos` = 4 lotes em andamento por consulta), relendo
`UnprocessedKeys` com backoff. O primeiro lote que falhar derruba a consulta e interrompe os demais.

### Resiliência da visão consolidada

Proteção própria do DynamoDB (instâncias `dynamodb` e `dynamodb-cliente-conta` do Resilience4j, isoladas da
fonte primária), em `ResilienciaDynamoDb`, programática (sem AOP). Valores em `resilience4j.*.instances` e
`consultas.dynamodb.*` no `application.yml`.

| Mecanismo | Regra | Resposta |
| --- | --- | --- |
| Prazo da requisição | O mesmo `PrazoDaRequisicao` da fonte primária (`consultas.prazo.*`, ADR-0006). Cada `Query`/`BatchGetItem` leva `apiCallTimeout` = menor entre `consultas.dynamodb.timeout-chamada` (1 s) e o que resta do prazo, então tentativas e backoff do SDK nunca passam dele; a espera pelos lotes paralelos e a releitura das `UnprocessedKeys` também param no prazo; sem prazo restante, a chamada nem sai | 504 com as sugestões |
| Retry | **Só o do SDK** (modo `standard`: throttling, 5xx, falha de conexão; nunca 4xx): 2 tentativas extras, backoff exponencial com jitter de 10 ms até 50 ms, também no throttling. Cada tentativa tem teto `consultas.dynamodb.timeout-tentativa` (100 ms), para que uma tentativa presa deixe prazo para outra. Nenhum retry do Resilience4j no DynamoDB | 502 (falha) ou 504 (tempo) |
| Circuit breaker `dynamodb` | Por chamada ao SDK, depois do retry dele: janela de 50, mínimo 20, abre com 50% de falhas, 5 s aberto, 5 no meio-aberto. Contam 5xx, falhas de conexão, timeouts com o tempo inteiro, throttling e `UnprocessedKeys` restantes. **Não contam** (ADR-0010): chamadas interrompidas pela própria aplicação (lote cancelado), timeouts encurtados pelo prazo da requisição (menos de 10 ms restantes), pool de conexões esgotado e 4xx que não são throttling (`ValidationException` → 502) | 503 + `Retry-After` (tempo aberto) |
| Bulkhead `dynamodb-cliente-conta` | Só CLIENTE/CONTA: 40 consultas simultâneas, sem espera. Abaixo do total: saturar CLIENTE/CONTA não impede OPERACAO | 503 + `Retry-After: 1` |
| Bulkhead `dynamodb` | Todas as consultas: 180 simultâneas, sem espera | 503 + `Retry-After: 1` |
| Rate limiter `dynamodb` | 3000 consultas por segundo, sem fila | 503 + `Retry-After` (período do limite) |
| Lotes por consulta | `consultas.dynamodb.max-lotes-simultaneos` (4) `BatchGetItem` em andamento por consulta CLIENTE/CONTA; os demais esperam vaga dentro do prazo | 504 se o prazo acabar |
| Pool | Cliente HTTP do SDK (Apache HttpClient 5) com `consultas.dynamodb.pool.*`: 300 conexões, conexão em 100 ms, 20 ms de espera por conexão, 1 s de leitura, TTL 5 min, ociosa até 30 s. Dimensionado pelo pior caso dos bulkheads, **validado na subida**: `max-conexoes ≥ (dynamodb − dynamodb-cliente-conta) + dynamodb-cliente-conta × max-lotes-simultaneos` = (180 − 40) + 40 × 4 = 300 | 503 + `Retry-After: 1` se esgotar (sem contar no circuito) |

Bulkheads e rate limiter valem por consulta (antes de qualquer I/O, inclusive o Valkey); o circuit breaker, por
chamada ao DynamoDB. Falha do SDK depois do retry → 502; throttling depois do retry ou `UnprocessedKeys`
restantes após 3 leituras → 503 com `Retry-After: 1`; item fora do formato → 502. Métricas
`resilience4j.circuitbreaker.*`, `resilience4j.bulkhead.*` e `resilience4j.ratelimiter.*` com a tag `name`
(`dynamodb`, `dynamodb-cliente-conta`).

## Cache por bloco (Valkey)

Só na visão consolidada; a rota da fonte primária nunca lê nem grava cache (ADR-0002).

- Chave `op:{id_operacao}:{bloco}` (nome canônico do bloco); valor = `texto_detalhe` em GZIP exatamente como veio
  do DynamoDB (sem recomprimir); TTL `consultas.cache.ttl` (default 5 min).
- Por requisição, uma leitura de todas as chaves operação × bloco a ler da tabela base, em `MGET`s de até
  `consultas.cache.chaves-por-comando` (100) chaves em pipeline; só os ausentes vão ao `BatchGetItem`, e os lidos
  são gravados **fora da requisição** (virtual thread, `SET ... PX` de uma chave em rodadas de 100 em pipeline, no
  máximo `max-gravacoes-simultaneas` = 32 gravações em andamento; sem vaga, descartada). Ex.: depois de
  `expand=parcelas,amortizacoes`, uma chamada `expand=parcelas,saldo` só lê `saldo` no DynamoDB (ADR-0011).
- Consulta com mais de `consultas.cache.max-chaves-por-consulta` (500) chaves, ou com menos de
  `prazo-minimo-restante` (20 ms) do prazo da requisição, nem lê nem grava no cache (`resultado=ignorado`).
- Premissa: Valkey standalone ou replication group, sem cluster mode. Só comandos de uma chave e `MGET` (que o
  cliente de cluster do Lettuce divide por slot); nenhum script ou transação multi-chave (ADR-0011).
- Vale para `OPERACAO`, `CLIENTE` e `CONTA`. A `Query` no GSI (a lista de operações do cliente/conta, que já traz o
  bloco `operacao`) **não** é cacheada: operação nova aparece na hora. Bloco indisponível não é cacheado.
- Fail-open: o Valkey é só otimização. Timeout curto do cliente (`spring.data.redis.timeout`, 10 ms; conexão em
  `connect-timeout`, 20 ms, para que um host inalcançável custe pouco até o circuito abrir) e circuit breaker
  próprio (`resilience4j.circuitbreaker.instances.valkey`); erro, lentidão ou circuito aberto = "nada em cache"
  (`resultado=indisponivel`) e a consulta segue no DynamoDB com a mesma resposta. O Valkey não entra no
  health/readiness.
- Cliente Lettuce (Spring Data Redis) com uma conexão compartilhada e multiplexada; sem pool.
- `consultas.cache.habilitado=false` desliga o cache (toda leitura vai ao DynamoDB).

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
chamada ao gateway, de fora para dentro: retry → circuit breaker → rate limiter → bulkhead de CLIENTE/CONTA (só
nessas consultas) → bulkhead total. Números em `resilience4j.*.instances.fontePrimaria*` no `application.yml`; a
classificação das falhas fica em `FalhasDoGateway` (ADR-0009).

| Mecanismo | Comportamento | Resposta |
| --- | --- | --- |
| Prazo da requisição | `consultas.prazo.*` por tipo de consulta (OPERACAO 150 ms, CLIENTE/CONTA 400 ms), contado desde a entrada da requisição. Limita a espera por vaga no fan-out, a abertura da conexão TCP (`timeout-conexao`, 50 ms), a espera por conexão do pool e pela resposta de cada tentativa, e o fan-out como um todo (ADR-0006) | 504 com as sugestões |
| Retry | Só o do Resilience4j (o Apache HttpClient não retenta): 2 tentativas extras, backoff exponencial de 10 ms (×2) com jitter de 50%. Só para 5xx e falha/timeout ao **abrir** a conexão; nunca 4xx, timeout de resposta, timeout do pool, circuito aberto, bulkhead ou rate limiter. Só se ainda restar `consultas.fonte-primaria.retry.prazo-minimo-restante` (50 ms) do prazo | a falha da última tentativa (502/504) |
| Circuit breaker | Janela de 50 chamadas, mínimo 20, abre com 50% de falhas (5xx e falhas de I/O, inclusive timeouts que tiveram o tempo configurado inteiro; 4xx e 404 contam como sucesso), 5 s aberto. Não contam: chamadas interrompidas pelo próprio fan-out, timeouts encurtados pelo prazo da requisição (um 504 não abre o circuito com o mainframe saudável) e recusas do rate limiter e do bulkhead. Meio-aberto com 16 chamadas (= `max-chamadas-simultaneas`); a recusa das chamadas além delas não interrompe as sondas em andamento, que terminam e decidem o circuito (ADR-0009) | 503 + `Retry-After` (tempo aberto) |
| Bulkhead `fontePrimaria-cliente-conta` (semáforo) | Só chamadas de consultas CLIENTE/CONTA: 40 simultâneas, espera de até 10 ms. Abaixo do total: o fan-out delas não impede OPERACAO | 503 + `Retry-After: 1` |
| Bulkhead `fontePrimaria` (semáforo) | Todas as chamadas: 50 simultâneas ao gateway (= pool), espera de até 10 ms por vaga | 503 + `Retry-After: 1` |
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
| `data` / `data_hora` | padrão do `DateTimeFormatter` | string `yyyy-MM-dd` / `yyyy-MM-ddTHH:mm:ss`; leitura estrita (data inexistente, como `2024-02-30`, é trancode inválido → 502) |
| `booleano` | caracteres de verdadeiro e falso (default `SN`) | `true`/`false` |
| `texto` | — | string sem os brancos das pontas |

Campo todo em branco vira `null`; data ou data-hora só de zeros (`0000-00-00`, `00000000`) também, convenção do
mainframe para "sem data" (ex.: `data_baixa_operacao` de operação ativa). Os layouts não têm sinal: o gerador de
stubs (`localstack/gerar_trancode.py`) recusa número negativo e decimal com mais casas que o `formato`.

## Evento de consumo (SQS)

Cada requisição às rotas de consulta, com sucesso ou erro, gera um evento de consumo publicado na fila SQS
`consultas-eventos-consumo` (ADR-0004, ADR-0008), para outro serviço levar a Firehose → S3 → Athena. Contrato
(campos, códigos de erro, evolução por `versao_evento`) em [`docs/eventos/evento-consumo.md`](docs/eventos/evento-consumo.md).

- JSON plano em `snake_case`: `versao_evento`, `timestamp`, `correlation_id`, `trace_id`, `consumidor`, `rota`,
  `tipo_consulta`, `id_operacao`, `cpf_cnpj_hmac`, `conta_hmac`, `blocos_solicitados`, `campos_solicitados`,
  `filtro`, `limite_operacoes`, `quantidade_operacoes_retornadas`, `blocos_indisponiveis`, `cache_hits` e
  `cache_misses` por bloco, `status_http`, `codigo_erro`, `duracao_ms`.
- CPF/CNPJ e conta só como HMAC-SHA256 (hex) com `consultas.eventos.segredo-hmac`, também dentro do `filtro`
  (com ou sem pontuação: `529.982.247-25` dá o mesmo HMAC de `52998224725`); nunca em claro. Blocos e campos
  malformados ficam fora do evento. `consumidor` é o mesmo valor normalizado de logs e métricas.
- Montado no fim do `ContextoRequisicaoFilter` (`RegistroDeConsumoHttp`). Os hits e misses de cache da requisição
  são contados no `CacheDaRequisicao`, aberto pela borda HTTP e propagado às threads filhas como o MDC.
- **Best-effort e assíncrono**: a requisição só põe o evento numa fila em memória limitada
  (`consultas.eventos.fila.capacidade`, 10.000); uma virtual thread monta lotes de até 10 (ou o que houver 1 s depois
  do primeiro evento) e envia cada lote num `SendMessageBatch`, em outra virtual thread. A requisição nunca espera
  o SQS nem falha por ele.
- Fila cheia, bulkhead sem vaga, circuito aberto, falha do SQS ou encerramento → evento descartado e contado em
  `eventos.consumo.descartados` (tag `motivo`).
- Resiliência própria: circuit breaker `sqs` (janela de 20, mínimo 5, 50% de falhas, 10 s aberto), bulkhead `sqs`
  (4 envios simultâneos, espera de até 1 s por vaga), timeout por tentativa (1 s) e da chamada (3 s) e retry só do
  SDK (`standard`).
- Encerramento gracioso: depois que o servidor web para de atender, o que restou na fila é enviado por até
  `consultas.eventos.fila.tempo-maximo-drenagem` (10 s) no total, envios em andamento incluídos. Todo evento
  termina enviado ou contado: o que não sai no tempo (ou chega à fila durante o encerramento) conta como
  `encerramento`. Uma falha inesperada no drenador descarta só aquele lote (`falha_envio`) e ele segue.
- `consultas.eventos.habilitado=false` desliga o evento (nada é montado nem enviado). Ligado, `segredo-hmac` (pelo
  menos 32 bytes, ex.: `openssl rand -base64 48`) e `sqs.url-fila` são obrigatórios (a aplicação não sobe sem
  eles). O segredo nunca aparece no `toString()` das propriedades.

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
| `x-consumidor` | Sem espaços nas pontas. Ausente: `desconhecido`. Fora de `[A-Za-z0-9._-]{1,64}`: `invalido` (o valor recebido não vai a log, evento nem métrica) |

- `correlation_id`, `consumidor`, `tipo_consulta` (ou `invalido`) e `rota` vão para o MDC e, ao final, só essas
  chaves são removidas (as do tracing, como `traceId`/`spanId`, ficam com quem as pôs). Todo Problem Details traz a
  propriedade `correlation_id`.
- Rota, consumidor, tipo de consulta e blocos pedidos são lidos por uma única regra (`RequisicaoDeConsulta`) para
  MDC, log de acesso, evento de consumo, prazo e métricas; só as rotas exatas `/v1/operacoes-credito` e
  `/v1/operacoes-credito/fonte-primaria` geram evento e contam em `consultas.blocos`.
- Logs em JSON (structured logging ECS do Spring Boot) por um `AsyncAppender` (`logback-spring.xml`).
  Perfil `human-logs` para texto legível em desenvolvimento.
- Um log de acesso por requisição (logger `acesso`): `metodo`, `uri`, `blocos`, `status`, `duracao_ms`,
  `cache_hits` e `cache_misses` por bloco (objeto `bloco → quantidade`, vazio na fonte primária ou com o cache
  desligado), parâmetros informados (`id_operacao`, `cpf_cnpj`, `conta`, `limite_operacoes`, `fields`, `filter`)
  e o MDC.
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
| `consultas.dependencia` (timer, um por chamada externa) | `dependencia` (`dynamodb`, `fonte_primaria`, `valkey`, `sqs`), `operacao` (`BatchGetItem`, `Query`... no DynamoDB; o bloco, `lista_operacoes_cliente` ou `lista_operacoes_conta` na fonte primária; `MGET`/`SET` no Valkey; `SendMessageBatch` no SQS), `indice` (GSI ou `nenhum`, só DynamoDB) |
| `consultas.cache` (contador, um incremento por bloco procurado no cache) | `bloco`, `resultado` (`hit`, `miss`; `indisponivel` com o Valkey fora, lento ou o circuito aberto; `ignorado` quando a consulta pula o cache por ter chaves demais ou pouco prazo). Taxa de acerto = `hit / (hit + miss)`; o evento de consumo continua só com hits e misses |
| `resilience4j.circuitbreaker.*` (estado, chamadas) | `name` (`valkey`, `dynamodb`, `fontePrimaria`, `sqs`), `state`/`kind` |
| `resilience4j.bulkhead.*`, `resilience4j.ratelimiter.*` | `name` (`dynamodb`, `dynamodb-cliente-conta`, `fontePrimaria`; bulkhead também `fontePrimaria-cliente-conta` e `sqs`) |
| `eventos.consumo.enviados` (contador; Prometheus `eventos_consumo_enviados_total`) | — |
| `eventos.consumo.descartados` (contador; Prometheus `eventos_consumo_descartados_total`) | `motivo` (`fila_cheia`, `bulkhead_cheio`, `circuito_aberto`, `falha_envio`, `encerramento`) |
| `eventos.consumo.fila` (gauge, eventos aguardando envio) | — |
| `consultas.etapa` (timer, um por etapa interna) | `etapa` (`consulta_gsi`, `batch`, `lista_operacoes`, `fan_out`, `filtro`); `indice` em `consulta_gsi` |

- `x-consumidor` vira tag como veio (id da API key no gateway); fora de `[A-Za-z0-9._-]{1,64}` vira `invalido`.
  Bloco fora do catálogo não vira tag. `id_operacao`, CPF/CNPJ e conta nunca são tag de métrica.
- Spans: um por requisição HTTP (com `consumidor`, `tipo_consulta`, `rota`, `correlation_id` e os
  identificadores da consulta), com filhos por etapa e por chamada externa. `traceparent` (W3C) recebido
  continua o trace e segue para o gateway da fonte primária.
  - OPERACAO na visão consolidada: `valkey MGET`, `dynamodb BatchGetItem` (só com blocos fora do cache) e
    `valkey SET` filho do span HTTP, mas fora da requisição (pode terminar depois da resposta).
  - CLIENTE/CONTA: `consulta_gsi` (com os `dynamodb Query`) e `batch` (com os `dynamodb BatchGetItem` dos lotes).
  - Fonte primária: `fan_out` (atributo `operacoes`) com um `fonte_primaria <bloco>` por operação × bloco
    pedido; 404 (bloco inexistente) não é erro. Em `CLIENTE`/`CONTA`, antes dele, a etapa `lista_operacoes`
    com a chamada `fonte_primaria lista_operacoes_cliente` (ou `_conta`).
  - `filtro`, quando há `filter`.
- Novos adapters (Valkey, SQS) e etapas usam `Observacoes.dependencia(...)` e `Observacoes.etapa(...)`
  (`config.observabilidade`), que fixam nomes e tags. A camada de aplicação mede etapas pela porta
  `ObservadorDeEtapas`, sem depender de Micrometer.
- CPF/CNPJ e conta saem mascarados em tudo o que o span leva de texto: nome, atributos, eventos (inclusive
  `exception.message` e `exception.stacktrace`, que trazem a URL do gateway com o documento ou a conta) e
  descrição do status (`MascaramentoSpanExporter`, que envolve todo exportador de span). O Collector mascara de
  novo, como defesa em profundidade (processor `transform/mascaramento`).
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
  desenvolvimento local: a tag não recebe patches de segurança. Serviços `dynamodb` e `sqs`.
- **WireMock `3.13.2`** (porta 8089), simulando o gateway do mainframe com os stubs de `wiremock/mappings`.
- **Valkey `9.0.8-alpine`** (porta 6379), cache por bloco, sem persistência.
- **app**, construída pelo `Dockerfile`.
- **OpenTelemetry Collector** `otel/opentelemetry-collector-contrib:0.165.0` (OTLP em `4317`/`4318`).
- **Jaeger v2** `jaegertracing/jaeger:2.23.0`: traces em http://localhost:16686.
- **Prometheus** `prom/prometheus:v3.16.0`: http://localhost:9090.
- **Grafana** `grafana/grafana:13.3.1`: http://localhost:3000 (anônimo, somente leitura; `admin`/`admin`
  para editar). Datasources Prometheus e Jaeger e o dashboard *Consultas de Operações de Crédito*
  (latência por tipo de consulta e rota, latência por dependência, erros, blocos mais pedidos, consumo
  por consumidor) são provisionados de `observabilidade/grafana/`.

O script `localstack/init/ready.d/00-eventos-consumo.sh` cria a fila SQS `consultas-eventos-consumo` dos eventos
de consumo; para ver os eventos: `docker compose exec localstack awslocal sqs receive-message --queue-url
http://localhost:4566/000000000000/consultas-eventos-consumo --max-number-of-messages 10`.

O script `localstack/init/ready.d/01-visao-consolidada.sh` cria a tabela `operacoes-credito`
(PK `numero_operacao`, SK `custodia_expand`), os GSIs esparsos `gsi-cliente` e `gsi-conta`
(SK `sk_gsi` = `<data_contratacao_operacao>#<numero_operacao>`, projeção `ALL`) e carrega a massa de
`localstack/massa/<numero_operacao>/` (um `<BLOCO>.json` por bloco, gravado em GZIP no `texto_detalhe`,
e `indices.env` com os atributos dos GSIs, que vão só no item `OPERACAO`).

Para ver a tabela no **NoSQL Workbench**: *Operation builder* → *Add connection* → *DynamoDB local*, host
`localhost`, porta `4566`. O Workbench assina com a região `localhost`; o LocalStack do compose roda com
`DYNAMODB_SHARE_DB=1` (um só DynamoDB para qualquer região), por isso a tabela criada em `sa-east-1` aparece.

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
CONSULTAS_EVENTOS_SQS_ENDPOINT=http://localhost:4566 \
CONSULTAS_EVENTOS_SQS_URL_FILA=http://localhost:4566/000000000000/consultas-eventos-consumo \
CONSULTAS_EVENTOS_SQS_CREDENCIAIS_ACCESS_KEY_ID=test CONSULTAS_EVENTOS_SQS_CREDENCIAIS_SECRET_ACCESS_KEY=test \
CONSULTAS_EVENTOS_SEGREDO_HMAC=segredo-local-somente-desenvolvimento \
./mvnw spring-boot:run
```

(Ou `CONSULTAS_EVENTOS_HABILITADO=false` no lugar das variáveis `CONSULTAS_EVENTOS_*`, sem evento de consumo.)

## Configuração

| Propriedade | Variável | Default |
| --- | --- | --- |
| `consultas.dynamodb.tabela` | `CONSULTAS_DYNAMODB_TABELA` | `operacoes-credito` |
| `consultas.dynamodb.regiao` | `AWS_REGION` | `sa-east-1` |
| `consultas.dynamodb.endpoint` | `CONSULTAS_DYNAMODB_ENDPOINT` | vazio (endpoint da AWS) |
| `consultas.dynamodb.credenciais.*` | `CONSULTAS_DYNAMODB_CREDENCIAIS_ACCESS_KEY_ID` / `..._SECRET_ACCESS_KEY` | vazio (cadeia padrão do SDK) |
| `consultas.cache.habilitado` | `CONSULTAS_CACHE_HABILITADO` | `true` |
| `consultas.cache.ttl` | `CONSULTAS_CACHE_TTL` | `5m` |
| `spring.data.redis.host` / `.port` (Valkey) | `CONSULTAS_VALKEY_HOST` / `CONSULTAS_VALKEY_PORTA` | `localhost` / `6379` |
| `spring.data.redis.timeout` (comando no Valkey) | `CONSULTAS_VALKEY_TIMEOUT` | `10ms` |
| `spring.data.redis.connect-timeout` | `CONSULTAS_VALKEY_CONNECT_TIMEOUT` | `20ms` |
| `consultas.cache.chaves-por-comando` / `.max-chaves-por-consulta` / `.prazo-minimo-restante` / `.max-gravacoes-simultaneas` | `CONSULTAS_CACHE_MAX_CHAVES_POR_CONSULTA` (`max-chaves-por-consulta`) | `100` / `500` / `20ms` / `32` |
| `consultas.dynamodb.timeout-chamada` | — | `1s` (teto de uma chamada ao SDK com as tentativas; numa requisição, limitado ao prazo restante) |
| `consultas.dynamodb.timeout-tentativa` | `CONSULTAS_DYNAMODB_TIMEOUT_TENTATIVA` | `100ms` (teto de cada tentativa do SDK) |
| `consultas.dynamodb.pool.*` | `CONSULTAS_DYNAMODB_MAX_CONEXOES` (`max-conexoes`) | `max-conexoes` 300 (validado contra os bulkheads na subida), `timeout-conexao` 100ms, `timeout-obter-conexao` 20ms, `timeout-resposta` 1s, `ttl` 5m, `max-ociosa` 30s |
| `consultas.dynamodb.retry.*` | — | `tentativas-extras` 2, `backoff-base` 10ms, `backoff-maximo` 50ms (retry do SDK, modo standard) |
| `resilience4j.circuitbreaker.instances.dynamodb.*` | — | janela `COUNT_BASED` de 50, mínimo 20 chamadas, 50% de falhas, 5s aberto, 5 chamadas no meio-aberto |
| `resilience4j.bulkhead.instances.dynamodb.*` / `.dynamodb-cliente-conta.*` | — | `max-concurrent-calls` 180 / 40, `max-wait-duration` 0ms |
| `consultas.dynamodb.max-lotes-simultaneos` | `CONSULTAS_DYNAMODB_MAX_LOTES_SIMULTANEOS` | `4` (lotes de `BatchGetItem` em andamento por consulta CLIENTE/CONTA) |
| `resilience4j.ratelimiter.instances.dynamodb.*` | — | `limit-for-period` 3000 por `1s`, `timeout-duration` 0s |
| `resilience4j.circuitbreaker.instances.valkey.*` | — | janela de 20 chamadas, mínimo 10, abre com 50% de falhas, 5 s aberto, 3 chamadas em meio-aberto |
| `consultas.fonte-primaria.url` | `CONSULTAS_FONTE_PRIMARIA_URL` | `http://localhost:8089` |
| `consultas.fonte-primaria.layouts` | — | `classpath:trancode/layouts/` |
| `consultas.fonte-primaria.pool.*` | — | `max-conexoes` 50, `max-conexoes-por-rota` 50, `ttl` 5m, `keep-alive` 30s, `timeout-conexao` 50ms, `timeout-obter-conexao` 50ms, `timeout-resposta` 1s (todos limitados ao prazo restante) |
| `consultas.fonte-primaria.fan-out.max-chamadas-simultaneas` | `CONSULTAS_FONTE_PRIMARIA_MAX_CHAMADAS_SIMULTANEAS` | `16` (teto de chamadas ao gateway em andamento por requisição; mínimo 1) |
| `consultas.fonte-primaria.retry.prazo-minimo-restante` | — | `50ms` (só retenta se restar isto do prazo da requisição) |
| `consultas.prazo.operacao` / `.cliente` / `.conta` | `CONSULTAS_PRAZO_OPERACAO` / `_CLIENTE` / `_CONTA` | `150ms` / `400ms` / `400ms` (prazo da requisição → 504) |
| `resilience4j.circuitbreaker.instances.fontePrimaria.*` | — | janela `COUNT_BASED` de 50, mínimo 20 chamadas, 50% de falhas, 5s aberto, 16 chamadas no meio-aberto |
| `resilience4j.bulkhead.instances.fontePrimaria.*` / `.fontePrimaria-cliente-conta.*` | — | `max-concurrent-calls` 50 / 40, `max-wait-duration` 10ms |
| `resilience4j.ratelimiter.instances.fontePrimaria.*` | — | `limit-for-period` 2000 por `1s`, `timeout-duration` 0s |
| `resilience4j.retry.instances.fontePrimaria.*` | — | `max-attempts` 3, `wait-duration` 10ms, backoff exponencial ×2, jitter 0.5 |
| `consultas.eventos.habilitado` | `CONSULTAS_EVENTOS_HABILITADO` | `true` |
| `consultas.eventos.segredo-hmac` | `CONSULTAS_EVENTOS_SEGREDO_HMAC` | vazio (obrigatório com o evento ligado; em produção, de um cofre de segredos) |
| `consultas.eventos.fila.capacidade` | `CONSULTAS_EVENTOS_CAPACIDADE_FILA` | `10000` |
| `consultas.eventos.fila.tamanho-lote` / `.intervalo-envio` / `.tempo-maximo-drenagem` | — | `10` (1 a 10) / `1s` / `10s` |
| `consultas.eventos.sqs.url-fila` | `CONSULTAS_EVENTOS_SQS_URL_FILA` | vazio (obrigatório com o evento ligado) |
| `consultas.eventos.sqs.endpoint` | `CONSULTAS_EVENTOS_SQS_ENDPOINT` | vazio (endpoint da AWS) |
| `consultas.eventos.sqs.regiao` | `AWS_REGION` | `sa-east-1` |
| `consultas.eventos.sqs.credenciais.*` | `CONSULTAS_EVENTOS_SQS_CREDENCIAIS_ACCESS_KEY_ID` / `..._SECRET_ACCESS_KEY` | vazio (cadeia padrão do SDK) |
| `consultas.eventos.sqs.timeout-tentativa` / `.timeout-chamada` | — | `1s` / `3s` |
| `resilience4j.circuitbreaker.instances.sqs.*` | — | janela `COUNT_BASED` de 20, mínimo 5 chamadas, 50% de falhas, 10s aberto, 2 chamadas no meio-aberto |
| `resilience4j.bulkhead.instances.sqs.*` | — | `max-concurrent-calls` 4, `max-wait-duration` 1s |
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
  breaker abrindo e voltando (inclusive com fan-out maior que as chamadas do meio-aberto), timeouts encurtados
  pelo prazo fora do circuito, bulkheads total e de CLIENTE/CONTA e rate limiter (503); `FontePrimariaConfigTest`
  mostra, contra um endereço não roteável, o timeout de conexão de 50 ms (conta no circuito) e o encurtado pelo prazo
  (não conta); `TracesIT` derruba e atrasa o gateway nas listas de cliente e conta e confere que nem os spans
  (eventos de exceção e status incluídos) nem os logs levam CPF/CNPJ ou conta; `IsolamentoFontePrimariaIT` mostra a visão
  consolidada saudável com o mainframe degradado); `ResilienciaVisaoConsolidadaIT` põe o LocalStack atrás de um
  Toxiproxy (`VisaoConsolidadaComToxiproxyContainers`, contexto próprio) e atrasa ou derruba o proxy para o 504 no
  prazo de cada tipo de consulta, o circuit breaker abrindo (503 + `Retry-After`), o rate limiter (503) e o
  isolamento do bulkhead de CLIENTE/CONTA em relação a OPERACAO. `DynamoDbConfigTest` conta, num servidor HTTP
  local, as tentativas do retry do SDK (3 em 5xx e throttling, 1 em 4xx).
  O cache fica desligado nos testes (`src/test/resources/application.properties`), exceto no `CacheIT`, que sobe
  o Valkey atrás de um Toxiproxy (`CacheContainers`): altera itens no DynamoDB depois da primeira chamada para
  saber de onde veio cada bloco, lê o Valkey direto pela porta do container e derruba ou atrasa o proxy para
  verificar o fail-open e o circuit breaker.
- O evento de consumo fica desligado nos testes (`consultas.eventos.habilitado=false`), exceto no
  `EventoDeConsumoIT` (`EventosDeConsumoContainers`), que lê a fila do LocalStack com o SDK e confere cada evento
  (HMAC, nenhum dado em claro, hits/misses de cache com o Valkey, erros, `trace_id`) e sobe uma segunda instância
  da aplicação para ver a fila em memória drenada no encerramento, e no `EventoDeConsumoSqsForaIT`, em que o SQS é
  um socket que nunca responde: a consulta segue igual e rápida e os eventos são descartados e contados.
- Nos testes, os prazos por tipo de consulta são de 10 s e o circuito da fonte primária só abre com 1000
  chamadas (`src/test/resources/application.properties`), para que containers frios e falhas provocadas de
  propósito não afetem outros testes; os testes de resiliência usam contexto próprio com valores pequenos.

O CI (`.github/workflows/ci.yml`, actions fixadas por SHA do commit) executa `./mvnw -B verify`, valida o
docker-compose e o Collector, constrói a imagem Docker e, num job à parte, roda `terraform fmt -check` e
`terraform init -backend=false` + `terraform validate` em `infra/api-gateway`. A imagem parte de bases Temurin
fixadas por tag exata e digest, roda como o usuário numérico `10001` e termina o processo em `OutOfMemoryError`
(`-XX:+ExitOnOutOfMemoryError`).
