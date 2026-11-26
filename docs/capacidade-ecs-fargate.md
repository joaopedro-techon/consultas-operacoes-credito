# Capacidade: ECS Fargate, DynamoDB e Valkey

Modelo de capacidade do serviço ECS Fargate `visao-consolidada` para a sua parte dos 60.000 req/s (itens 5, 6 e 7 de `docs/prompt_detalhes_ajustes_3.md`). O serviço `fonte-primaria` (mainframe) é outro repositório, `consultas-operacoes-credito-mainframe` (ADR-0017), com o próprio modelo de capacidade: chamadas ao mainframe, cota, bulkheads, circuitos e task.

Legenda, aplicada a todos os números:
- **(P) premissa:** valor assumido, a confirmar com dados de produção.
- **(E) estimativa:** derivada das premissas.
- **(M) medido:** a preencher com o k6 (`carga/README.md`), seção 7.

Nada aqui foi medido ainda.

## Resumo

| | `visao-consolidada` |
| --- | --- |
| Demanda-alvo | 54.000 req/s (90% dos 60.000; os outros 10% vão à fonte primária) **(P)** |
| Perfil | CPU-bound (GZIP, JSON, filtro, métricas) |
| Task | **2 vCPU / 4 GB** (heap 3 GB, G1) **(E)** |
| RPS-alvo por task | **550 req/s** (≈ 60% da CPU) **(E)** |
| Alvo `ALBRequestCountPerTarget` (por minuto) | **33.000** (550 × 60) |
| Tasks no pico | ~99 **(E)** |
| min / max tasks | **12 / 150** |
| Cooldown out / in | 60 s / 300 s |
| Deploy | 200% / 100% |
| Política extra | CPU média 60% |
| Vazão real da frota | 54.000 req/s com ~99 tasks |

Ajustes recomendados (detalhes na seção 5):
- **Resiliência:** o rate limiter `dynamodb` cai de 3.000 para **~1.000 req/s por task**. Hoje ele está acima do que a CPU de uma task de 2 vCPU entrega.
- **Infraestrutura:**
  - **DynamoDB:** cerca de 150 mil RRU/s, e até 500 mil com o Valkey fora. É preciso aumentar a cota da tabela e usar warm throughput.
  - **Valkey:** cerca de 54 mil `MGET`/s e 262 mil `SET`/s, ~10 Gbit/s. É preciso **cluster mode** ou Serverless, e revisar a premissa do ADR-0011.
  - **API Gateway:** throttle de conta e timeout da integração (seção 6).

## 1. Premissas

| Premissa | Valor | Tipo |
| --- | --- | --- |
| Demanda total | 60.000 req/s: 54.000 na visão consolidada, 6.000 na fonte primária | (P) |
| Mix por tipo | 50% OPERACAO, 25% CLIENTE, 25% CONTA | (P) |
| Blocos por consulta (`expand`) | 4: `operacao` + `originacao` + 2. Dos 2 extras, 1 é bloco-lista | (P) |
| Operações por cliente/conta | 5 em média. `limite_operacoes` vai até 500 | (P) |
| DynamoDB | p50 5 ms, p99 20 ms | (P) |
| Valkey | ~1 ms | (P) |
| Acerto do cache de blocos (Valkey) | 60% das chaves | (P) |
| Acerto do cache de custódia | 90% (TTL 24 h, compartilhado com o serviço da fonte primária) | (P) |
| Tamanho de um item do DynamoDB (`texto_detalhe` em GZIP) | ≤ 4 KB → 0,5 RRU por leitura eventualmente consistente | (P) |
| Custo de CPU por requisição na visão consolidada | OPERACAO 1,2 ms, CLIENTE/CONTA 3,0 ms (mix 2,1 ms), com tracing a 100% e evento de consumo | (P), **medir** |
| Um vCPU do Fargate | 1 hyperthread x86; utilização-alvo de 60% | (P) |

Lei de Little: **L = λ × W**. L é o número de itens em andamento (requisições, chamadas, conexões), λ é a vazão e W é o tempo de cada um.

## 2. Fonte primária

O dimensionamento do serviço `fonte-primaria` (chamadas ao mainframe por consulta, cota, latência, concorrência,
circuito e retry, task e borda) foi para o repositório `consultas-operacoes-credito-mainframe` (ADR-0017). A
numeração das seções seguintes foi mantida.

## 3. Visão consolidada: task, escala e resiliência

### 3.1 RPS por task

```
CPU por requisição (mix) = 0,5 × 1,2 ms + 0,5 × 3,0 ms = 2,1 ms        (P, medir)
capacidade de 2 vCPU a 100% = 2.000 ms / 2,1 ms ≈ 950 req/s           (E)
a 60% da CPU (alvo)         ≈ 570 → RPS-alvo 550 req/s por task       (E)
a 80% (início da degradação do p99) ≈ 760 req/s                        (E)
```

O CLIENTE/CONTA custa mais porque são ~5 operações × 4 blocos = 20 blocos para descomprimir, interpretar, filtrar e serializar, contra 4 na OPERACAO.

O tracing a 100% (o tail sampling está no Collector) e o evento de consumo por requisição entram nesse custo. Meça com `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1` para ver quanto o head sampling economizaria.

### 3.2 Por que 2 vCPU / 4 GB

O preço do Fargate é linear em vCPU e GB. Lista us-east-1 x86: US$ 0,04048 por vCPU-hora e US$ 0,004445 por GB-hora. Em sa-east-1 é mais caro, mas a proporção é a mesma. Por isso, a escolha é sobre eficiência e granularidade, não sobre preço unitário.

| Task | US$/h por task | RPS por task (E) | Tasks no pico | US$/h no pico | Observação |
| --- | --- | --- | --- | --- | --- |
| 1 vCPU / 2 GB | 0,049 | < 275 | > 196 | > 9,7 | A JVM cai no SerialGC, e JIT e GC disputam o único vCPU com as requisições. O p99 piora e o overhead fixo da JVM (~400 MB fora do heap) é pago 2× mais vezes |
| **2 vCPU / 4 GB** | **0,099** | **550** | **~99** | **~9,8** | **G1 pela ergonomia. 4 GB é o mínimo do Fargate para 2 vCPU. Degraus de escala de ~1%** |
| 4 vCPU / 8 GB | 0,198 | ~1.100 | ~50 | ~9,9 | Degraus maiores, subida e aquecimento mais lentos e mais impacto por task perdida. Mais 4 GB de heap sem uso |

Memória com 2 vCPU / 4 GB **(E)**:
- **Heap.** São 3 GB (`MaxRAMPercentage=75`). A alocação estimada é de ~550 req/s × ~150 KB ≈ 80 MB/s, o que dá uma GC jovem a cada ~10 s com pausas de 5 a 15 ms.
- **Virtual threads.** As pilhas ficam no heap e somam poucos MB: ~10 requisições em andamento, ≤ 180 no bulkhead.
- **Fora do heap.** Os 25% restantes (~1 GB) cobrem metaspace, code cache, buffers diretos (Lettuce/Netty, Apache HttpClient) e pilhas das threads de plataforma.

Graviton (ARM64) custa ~20% menos por vCPU. Exige imagem multi-arquitetura (a `eclipse-temurin` é) e uma nova medição. É a primeira otimização de custo a testar.

### 3.3 Tasks, autoscaling e tempo de reação

- **Tasks no pico:** 54.000 ÷ 550 ≈ **99 (E)**.
- **max = 150**, que cobre três coisas:
  - +25% de pico acima do previsto (123 tasks);
  - perda de uma das 3 AZs: as 2 restantes precisam de 99 tasks, ~1,5× o que tinham;
  - desequilíbrio do ALB.
- **min = 12 (P):** 4 por AZ. Isso pressupõe um vale de ~10% do pico (~5.400 req/s). Ajuste com o perfil real.
- **Alvo `ALBRequestCountPerTarget` = 33.000.** A métrica é a soma de requisições por target **por minuto**, então o alvo é 550 × 60. A segunda política, de CPU média em 60%, protege contra erro na estimativa do custo por requisição.
- **Tempo de reação ao aumento de carga (E):**
  - o alarme do target tracking de scale-out precisa de 3 pontos de 1 min acima do alvo, ou seja, **até ~3 min**;
  - a task nova leva **~60 a 90 s**: provisionamento, pull da imagem, subida da JVM e 2 health checks de 10 s;
  - em seguida vêm 60 s de slow start no ALB.

  Total: **~4 a 5 min**.
- **Folga durante esse tempo:** do alvo (550) ao início da degradação (~760) há **+38% de carga absorvível**, e até a saturação (~950), +70%.
- **Se a carga crescer mais rápido que isso** (ex.: de 10 mil para 54 mil em minutos):
  - **scheduled scaling**, que sobe o `min` antes dos picos conhecidos;
  - alvo mais baixo (450 → 27.000), mais caro;
  - e, para o ALB, reserva de LCU ou pre-warm.
- **Cooldowns:** 60 s para scale-out e 300 s para scale-in, para evitar oscilação: o JIT de uma task nova leva ~1 min para chegar ao custo estável.

### 3.4 Concorrência (Little) × bulkheads, pool e rate limiter

Requisições em andamento por task **(E)**:

| Situação | OPERACAO | CLIENTE/CONTA | Total |
| --- | --- | --- | --- |
| 550 req/s, normal (10 ms / 25 ms) | 2,8 | 6,9 | **~10** |
| 550 req/s, DynamoDB em p99 (50 ms / 120 ms) | 14 | 33 | ~47 |
| 1.000 req/s, degradado | 25 | 60 | ~85 |

Validação dos valores atuais:
- **Bulkhead `dynamodb` de 180.** Fica muito acima do normal (~10) e só limita sob degradação. Pode ficar.
- **Bulkhead `dynamodb-cliente-conta` de 40.** Cabe no normal (7) e no DynamoDB em p99 (33). Com a task acima do alvo e o DynamoDB lento (60), recusa CLIENTE/CONTA primeiro, que é o comportamento desejado (ADR-0007). Pode ficar.
- **Pool de 300 conexões.** Respeita a regra do ADR-0010 ((180 − 40) + 40 × 4). O uso real é de ~660 chamadas/s × 6 ms ≈ 4 conexões por task. É folgado, mas o pool é preguiçoso (não abre o que não usa). Pode ficar.
- **Rate limiter `dynamodb` de 3.000/s por task: incoerente.** A task de 2 vCPU satura em ~950 req/s, então o limite nunca dispara antes da CPU, e uma task que recebe carga demais (ALB desequilibrado, frota subdimensionada durante o scale-out) degrada a latência de todas as suas requisições.
  - **Recomendação: ~1.000/s por task (≈ saturação medida × 1,05).** Recalibre com o joelho do k6 (`carga/README.md`) e com `limit-refresh-period` de 100 ms (100 por período), para não haver rajada no início do segundo.
- Virtual threads ligadas e `-XX:MaxRAMPercentage=75`: adequados.
- Evento de consumo: ~550 eventos/s por task → ~55 `SendMessageBatch`/s. O bulkhead `sqs` de 4 envios simultâneos a ~20 ms comporta ~200 lotes/s, e a fila em memória de 10.000 cobre ~18 s de SQS fora.

### 3.5 DynamoDB

Leituras eventualmente consistentes. Itens ≤ 4 KB custam 0,5 RRU cada **(P)**. Uma chave pedida no `BatchGetItem` e inexistente também consome o mínimo de 0,5 RRU **(P, conservador)**. Por isso, sem custódia em cache, a descoberta é uma `Query` pela PK (ADR-0020), que só cobra o que existe. As linhas abaixo ainda supõem o `BatchGetItem` nas 4 custódias e ficam conservadoras para a OPERACAO. Recalibre com a métrica `consultas.dynamodb.capacidade_consumida` (por `operacao` e `indice`).

| Tráfego (54.000 req/s) | Chaves no DynamoDB por consulta | RRU/s **(E)** |
| --- | --- | --- |
| OPERACAO 27.000/s: 4 blocos × 40% de miss × (90% × 1 custódia + 10% × 4) | 2,08 | 28.000 |
| CLIENTE/CONTA 27.000/s: `Query` no GSI (≤ 4 KB → 0,5) + 5 operações × 4 blocos × 40% (custódia vem do GSI) | 8 + Query | 121.500 |
| **Total com o cache** | | **~150.000** |
| **Valkey fora (fail-open):** OPERACAO 4 custódias × 4 blocos, CLIENTE/CONTA 20 blocos | | **~500.000** |

Consequências:
- **O cache de custódia vale mais que o de blocos na OPERACAO.** Sem ele, cada miss de bloco custa 4 chaves. A 90% de acerto, o fator médio é 1,3×; a 0%, 4×.
- **Cota da tabela.** O limite padrão de throughput de uma tabela on-demand fica muito abaixo de 150 mil RRU/s (padrão da conta: 40.000 RRU/s por tabela). Ações:
  - pedir o aumento para **≥ 500 mil RRU/s**, para que o Valkey fora não vire throttling e circuito aberto;
  - configurar **warm throughput** da tabela **e do GSI** para o pico. Uma tabela on-demand nova não absorve um degrau para 150 mil de imediato.
- **On-demand × provisionado (E, preço de lista us-east-1).**

  | Modo | Preço de lista | Custo a 150 mil RRU/s constantes |
  | --- | --- | --- |
  | On-demand | US$ 0,125 por milhão de RRU | ~US$ 67/h (~US$ 49 mil/mês) |
  | Provisionado | US$ 0,00013 por RCU-hora | ~US$ 19/h (~US$ 14 mil/mês) |

  O provisionado fica mais barato se a média do dia passar de **~29%** do pico. Recomendação:
  - **começar em on-demand**, com warm throughput e máximo configurado, enquanto o perfil é desconhecido;
  - **migrar para provisionado** com autoscaling (alvo de 70%) e capacidade reservada quando o perfil diário estiver medido;
  - manter on-demand se os picos forem muito abruptos para o autoscaling do DynamoDB (minutos).
- **Partições.** Cada partição sustenta até 3.000 RCU. Um cliente muito consultado concentra leituras numa partição do GSI de cliente; acompanhe `ThrottledRequests` por índice.
- **Requisições.** São ~63 mil chamadas ao SDK por segundo: ~12 mil `BatchGetItem` de OPERACAO com algum miss, 27 mil `Query` e ~24 mil `BatchGetItem` de CLIENTE/CONTA. Ficam em ~650 por task, sem limite de requisições a observar além da capacidade.

### 3.6 Valkey (ElastiCache)

| Operação **(E)** | Por segundo |
| --- | --- |
| `MGET` (1 por consulta; OPERACAO com 4 blocos + a chave `custodia:`, CLIENTE/CONTA com ~20) | 54.000 |
| Chaves lidas | 675.000 |
| Valores devolvidos (60% de acerto) × ~2 KB | ~389.000 → **~5,9 Gbit/s** |
| `SET ... PX` das chaves que faltaram (fora da requisição) | **~262.000** (83% de CLIENTE/CONTA) → **~4 Gbit/s** |

Num replication group **sem cluster mode**, que é a premissa do ADR-0011, todas as gravações vão para **um único primário**. Esse primário recebe ~262 mil `SET`/s e ~4 Gbit/s de entrada, e manda o mesmo volume de saída para cada réplica. As leituras também vão para ele, a menos que o Lettuce leia das réplicas.

Isso está no limite, ou acima, de um nó grande. Recomendação:
- **Ligar o cluster mode com 4 a 6 shards** (ex.: `cache.r7g.xlarge` com 1 réplica cada), ou usar **ElastiCache Serverless para Valkey**, e validar com o k6. O ADR-0011 já deixou o código pronto: `MGET` dividido por slot no cliente de cluster e nenhum comando multi-chave além dele.
- **Reduzir a gravação**, se o custo pesar:
  - TTL maior, porque menos miss significa menos `SET`;
  - ou não gravar no cache os blocos de CLIENTE/CONTA, que são 83% das gravações. O preço é perder o acerto nessas consultas e levar ~108 mil RRU/s a mais ao DynamoDB.
- O cache se paga. Sem ele, o DynamoDB precisaria de ~350 mil RRU/s a mais, ~US$ 160/h em on-demand, contra alguns US$/h de um cluster Valkey.

Conexões: o Lettuce usa uma conexão compartilhada por task, ~150 no pico. Com cluster mode, uma por shard por task.

## 4. Isolamento do serviço da fonte primária

- **Limitação.** A cota do mainframe roda no serviço `fonte-primaria`, com imagem, ECS e repositório próprios (ADR-0017), e nunca recusa chamadas da visão consolidada. Bulkheads, pool e circuitos também são separados.
- **O que os dois serviços compartilham:**
  - o **Valkey**, para a chave `custodia:<id>`. O CB `valkey` e o fail-open protegem cada serviço de um Valkey lento;
  - o **ALB** e o **API Gateway**.

## 5. Ajustes recomendados no `application.yml`

| Propriedade | Valor previsto | Recomendação | Motivo |
| --- | --- | --- | --- |
| ratelimiter `dynamodb` | 3.000 / 1 s | **~1.000 / 1 s** (ou 100 / 100 ms), recalibrar com o k6 | §3.4: 3.000 nunca dispara antes da CPU |
| bulkheads `dynamodb` / `dynamodb-cliente-conta`, pool DynamoDB | 180 / 40 / 300 | **mantidos** ✓ | §3.4 |

## 6. Limites da plataforma fora da aplicação

- **API Gateway (`infra/api-gateway`):**
  - **Throttle de conta:** o padrão é **10.000 req/s** por região, contra 60.000 de demanda. É preciso pedir o aumento, além de um Usage Plan coerente por consumidor.
  - **Timeout da integração:** `integration_timeout_ms` tem padrão de 1.000 ms, abaixo dos prazos da fonte primária (2,5 s e 6 s). O gateway cortaria antes da aplicação. A rota fonte primária precisa de **≥ 6.500 ms**.
  - **Throttle por método na rota fonte primária:** ver o modelo de capacidade do repositório do mainframe.
  - **Custo:** um REST API a 60 mil req/s é um custo relevante (cobrado por milhão de requisições); vale comparar com HTTP API ou com o acesso direto ao ALB.
- **ALB.**
  - Escala sozinho, mas um degrau súbito até 60 mil req/s pede reserva de LCU.
  - A ~10 KB por resposta, são ~2 TB/h processados, ou ~2.000 LCU/h só de banda.
- **Fargate:** a cota de vCPU On-Demand da conta precisa cobrir o pico (150 × 2 ≈ 300 vCPU, mais as tasks do serviço da fonte primária) e o deploy a 200% (~600 vCPU).
- **SQS** (evento de consumo): ~6.000 `SendMessageBatch`/s, sem limite na fila standard, mas com custo próprio por requisição.
- **OTel Collector.** Com o tracing a 100%, recebe os spans de 60 mil req/s para fazer o tail sampling. Ele precisa ser dimensionado à parte; o ponto fixo do k6 com o Collector ligado mostra o custo do lado da aplicação.

## 7. Como validar com o k6

Procedimento completo em `carga/README.md`: limitar o container a 2 vCPU / 4 GB, rampa até o joelho e extrapolação para o Fargate.

| Número | Como medir | Medido (M) |
| --- | --- | --- |
| CPU por requisição OPERACAO / CLIENTE / CONTA (P: 1,2 / 3,0 ms) | `MIX_*` com um tipo só; CPU (núcleos) × 1000 ÷ RPS; com o cache ligado e desligado | |
| RPS-alvo por task da visão consolidada (E: 550) | Joelho da rampa (`MODO=rampa`) × 0,7, convertido pelo fator do Fargate; confirmar com 1 task na AWS | |
| Joelho e saturação (E: 760 / 950) | Rampa: último degrau com os thresholds verdes; CPU no limite | |
| Acerto do cache de blocos e de custódia (P: 60% / 90%) | Produção: `consultas.cache{resultado}` e `consultas_custodia_descoberta_total{resultado="cache"}` | |
| RRU/s do DynamoDB (E: 150 mil) | Produção/homologação: `ConsumedReadCapacityUnits` da tabela e do GSI ÷ req/s | |
| Valkey (E: 54 mil `MGET`/s, 262 mil `SET`/s) | `GetTypeCmds`/`SetTypeCmds`, `NetworkBytesIn/Out` e `EngineCPUUtilization` no ElastiCache | |
