# Contrato: evento de consumo (`versao_evento` 1)

Cada requisição às rotas de consulta (`GET /v1/operacoes-credito` e `GET /v1/operacoes-credito/fonte-primaria`),
com sucesso ou erro, gera um evento de consumo publicado na fila SQS `consultas-eventos-consumo` (ADR-0004,
ADR-0008). Um serviço fora deste repositório o leva a Firehose → S3 → Athena. Rotas fora dessas (ex.: `/actuator`)
não geram evento.

A publicação é best-effort: o evento pode se perder (fila em memória cheia, SQS fora, circuito aberto,
encerramento). As perdas são contadas em `eventos.consumo.descartados`. Use o evento para perguntas de negócio
(campos e filtros mais usados, consumo por consumidor ao longo do tempo), não como auditoria nem para cobrança
exata.

## Mensagem

- Uma mensagem SQS por evento (enviadas em lotes de até 10 por `SendMessageBatch`), corpo em JSON UTF-8.
- JSON plano em `snake_case`: valores escalares e listas de texto; os únicos objetos são `cache_hits` e
  `cache_misses` (mapa `bloco → quantidade`).
- Campo opcional sem valor fica **fora** do JSON (não vem `null`). Listas e os mapas de cache vêm sempre,
  vazios quando for o caso.
- Sem ordem garantida entre mensagens e sem deduplicação: o SQS padrão entrega pelo menos uma vez; use
  `correlation_id` + `timestamp` para descartar repetidos se precisar.

## Campos

| Campo | Tipo | Sempre? | Conteúdo |
| --- | --- | --- | --- |
| `versao_evento` | inteiro | sim | Versão do schema (hoje `1`) |
| `timestamp` | texto ISO-8601 UTC | sim | Início da requisição (ex.: `2026-11-23T10:15:30.123Z`) |
| `correlation_id` | texto | sim | `x-correlationid` recebido ou gerado (o mesmo devolvido na resposta) |
| `trace_id` | texto (32 hex) | não | Trace W3C da requisição |
| `consumidor` | texto | sim | `x-consumidor` como veio (id da API key no API Gateway) ou `desconhecido` |
| `rota` | texto | sim | `visao_consolidada` ou `fonte_primaria` |
| `tipo_consulta` | texto | não | `OPERACAO`, `CLIENTE`, `CONTA` ou `invalido`; fora se o header não veio |
| `id_operacao` | inteiro | não | `id_operacao` informado, se numérico |
| `cpf_cnpj_hmac` | texto (64 hex) | não | HMAC-SHA256 do `cpf_cnpj` informado, como veio |
| `conta_hmac` | texto (64 hex) | não | HMAC-SHA256 da `conta` informada, como veio |
| `blocos_solicitados` | lista de texto | sim | `operacao`, `originacao` e os blocos do `expand`, só nomes válidos, na ordem pedida |
| `campos_solicitados` | lista de texto | sim | Itens do `fields` bem formados (`bloco.campo`, `bloco.campo.subcampo`) |
| `filtro` | texto | não | `filter` com CPF/CNPJ e conta trocados pelo HMAC (ver abaixo) |
| `limite_operacoes` | inteiro | não | `limite_operacoes` informado, se numérico |
| `quantidade_operacoes_retornadas` | inteiro | sim | Operações em `data`; `0` em erro |
| `blocos_indisponiveis` | lista de texto | sim | Blocos de `x-blocos-indisponiveis` |
| `cache_hits` | objeto `bloco → inteiro` | sim | Blocos lidos do cache (um por operação × bloco); vazio na fonte primária ou com o cache desligado |
| `cache_misses` | objeto `bloco → inteiro` | sim | Blocos procurados e ausentes no cache (Valkey fora ou circuito aberto também conta) |
| `status_http` | inteiro | sim | Status da resposta |
| `codigo_erro` | texto | não | Só com `status_http` ≥ 400 (tabela abaixo) |
| `duracao_ms` | inteiro | sim | Duração da requisição na aplicação, em milissegundos |

| `status_http` | `codigo_erro` |
| --- | --- |
| 400 | `requisicao_invalida` |
| 404 | `operacao_nao_encontrada` |
| 405 | `metodo_nao_permitido` |
| 406 | `formato_nao_aceito` |
| 500 | `erro_interno` |
| 502 | `falha_na_fonte` |
| 503 | `fonte_indisponivel` |
| 504 | `tempo_esgotado` |
| outro ≥ 400 | `http_<status>` |

## Dados pessoais

CPF/CNPJ e conta **nunca** vão em claro: só como HMAC-SHA256 em hex minúsculo com o segredo da aplicação
(`consultas.eventos.segredo-hmac`). O mesmo valor dá sempre o mesmo HMAC enquanto o segredo não mudar, o que
permite contar e cruzar consumo por cliente ou conta sem guardar o dado. Trocar o segredo quebra essa
correspondência com eventos antigos (não muda o schema).

No `filtro`, o argumento de toda comparação cujo seletor termina em `cpf_cnpj`, `cpf`, `cnpj` ou `conta` vira o
HMAC (cada item de `=in=`/`=out=`, sem as aspas), e qualquer CPF/CNPJ solto com DV válido também. Ex.:
`operacao.cpf_cnpj==52998224725;operacao.situacao_operacao_credito==ATIVA` →
`operacao.cpf_cnpj==<hmac>;operacao.situacao_operacao_credito==ATIVA`.

Blocos e campos malformados (que poderiam ser um dado pessoal digitado no lugar errado) ficam fora de
`blocos_solicitados` e `campos_solicitados`.

## Exemplo

```json
{
  "versao_evento": 1,
  "timestamp": "2026-11-23T10:15:30.123Z",
  "correlation_id": "4f1c2b8e-9d3a-4e57-b1c0-7a2f6e8d9c10",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "consumidor": "sistema-custodia",
  "rota": "visao_consolidada",
  "tipo_consulta": "CLIENTE",
  "cpf_cnpj_hmac": "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b",
  "blocos_solicitados": ["operacao", "originacao", "parcelas"],
  "campos_solicitados": ["parcelas.numero_parcela_amortizacao"],
  "filtro": "parcelas.situacao_parcela==ABERTA",
  "limite_operacoes": 2,
  "quantidade_operacoes_retornadas": 2,
  "blocos_indisponiveis": [],
  "cache_hits": {"originacao": 2},
  "cache_misses": {"parcelas": 2},
  "status_http": 200,
  "duracao_ms": 18
}
```

## Evolução

- **Aditivo**: campo novo opcional, valor novo de `codigo_erro`, `tipo_consulta` ou bloco não mudam
  `versao_evento`. Consumidores devem ignorar campos desconhecidos.
- **Incompatível** (remover ou renomear campo, mudar tipo ou significado): incrementa `versao_evento` e
  atualiza este documento; durante a transição, o consumidor do SQS trata as duas versões.

| `versao_evento` | Mudança |
| --- | --- |
| 1 | Versão inicial |
