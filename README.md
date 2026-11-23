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
| `domain` | `Bloco`, `TipoConsulta`, `CriterioConsulta`, `OperacaoConsultada` e o catálogo de campos em `domain.catalogo` (sem framework) |
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

| Situação | Status |
| --- | --- |
| Operação encontrada | 200 |
| Algum bloco pedido ausente na visão consolidada | 200 + `x-blocos-indisponiveis` |
| Operação inexistente | 404 (Problem Details) |
| `x-tipo-consulta` ausente/inválido, `id_operacao` ausente/não numérico, parâmetro incoerente com o tipo (`cpf_cnpj`, `conta` ou `limite_operacoes` em `OPERACAO`; `id_operacao` em `CLIENTE`/`CONTA`) | 400 (Problem Details) |
| Bloco desconhecido no `expand` (inclusive maiúsculas ou item vazio) | 400 (Problem Details listando os blocos válidos) |
| `CLIENTE` ou `CONTA` com parâmetros coerentes | 501 (ainda não disponível) |

## Fonte primária

`GET /v1/operacoes-credito/fonte-primaria` aceita os mesmos headers e parâmetros e devolve o mesmo corpo da
visão consolidada; muda só a porta de saída que o caso de uso recebe (Strategy). Sem cache (ADR-0002).
Por enquanto atende `OPERACAO`; `CLIENTE`/`CONTA` nessa rota respondem 501.

- Gateway do mainframe via `@HttpExchange` (`GatewayMainframeClient`) sobre `RestClient` + Apache HttpClient 5
  com pool configurável e sem retry próprio. Uma chamada por bloco pedido
  (`GET /mainframe/operacoes/{id}/blocos/{bloco}`), em paralelo em virtual threads com o contexto da
  requisição. 404 do gateway: bloco indisponível (`null` + `x-blocos-indisponiveis`); sem o bloco `operacao`,
  404 da API. Outros erros ou trancode fora do layout: 502 (sem resposta parcial).
- `x-correlationid` é repassado em toda chamada ao gateway; o `traceparent` (W3C) é propagado pela
  observação do `RestClient`.
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
subcampos, nomes únicos) e será usado por `fields` e `filter`. Incluir ou remover campo = editar o YAML;
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
  nomes, pares `chave=valor`/RSQL/JSON e CPF/CNPJ soltos com DV válido (`MascaramentoLogCustomizer`).
- O MDC chega às threads filhas por Micrometer Context Propagation (`MdcThreadLocalAccessor`): envolver a
  tarefa com `ContextSnapshotFactory.captureAll().wrap(...)` ou o executor com `ContextExecutorService.wrap(...)`.

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
trancode do bloco e grava `wiremock/mappings/<id_operacao>.json`: as duas fontes servem a mesma massa.

| `id_operacao` | Situação | CPF/CNPJ | Conta | Cenário |
| --- | --- | --- | --- | --- |
| 100000001 | ATIVA | 52998224725 | 434115000065874 | 12 parcelas, 5 pagas, parcela 6 com amortização parcial e 2 dias de atraso |
| 100000002 | LIQUIDADA | 52998224725 | 434115000065874 | antecipação liquidando 3 parcelas (`BAIXA_INTEGRAL_MAIS_UMA_PARCELA`) |
| 100000003 | ATIVA | 52998224725 | 434115000099991 | sem recebimentos; **sem os blocos `plano` e `saldo`** |
| 100000004 | ATIVA | 12ABC34501DE35 (CNPJ alfanumérico) | 434115000077712 | 10 parcelas, 3 pagas |
| 100000005 | LIQUIDADA | 12ABC34501DE35 | 434115000077712 | 4 parcelas pagas no vencimento |
| 100000006 | ATIVA | 11144477735 | 434115000088823 | 8 parcelas, 2 pagas |

O cliente 52998224725 tem três operações em duas contas; a conta 434115000065874 e a 434115000077712 têm
duas operações cada.

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

## Testes

```bash
./mvnw verify
```

Requer JDK 25 e Docker. `verify` roda:

- testes unitários e ArchUnit (Surefire);
- testes HTTP de caixa-preta `*IT` (Failsafe), com a aplicação inteira e o LocalStack via Testcontainers,
  usando a mesma imagem, o mesmo script de init e a mesma massa do docker-compose, e o WireMock com os
  mesmos stubs de trancode (`FontePrimariaIT` compara as duas rotas para cada operação da massa).

O CI (`.github/workflows/ci.yml`) executa `./mvnw -B verify` e constrói a imagem Docker.
