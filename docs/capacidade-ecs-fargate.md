# Capacidade: ECS Fargate, mainframe, DynamoDB e Valkey

Modelo de capacidade para 60.000 req/s (itens 5, 6 e 7 de `docs/prompt_detalhes_ajustes_3.md`), com a arquitetura do ADR-0015: dois serviços ECS Fargate com a mesma imagem, `visao-consolidada` e `fonte-primaria`.

Legenda, aplicada a todos os números:
- **(P) premissa:** valor assumido, a confirmar com dados de produção.
- **(E) estimativa:** derivada das premissas.
- **(M) medido:** a preencher com o k6 (`carga/README.md`), seção 7.

Nada aqui foi medido ainda.

## Resumo

| | `visao-consolidada` | `fonte-primaria` |
| --- | --- | --- |
| Demanda-alvo | 54.000 req/s (90%) **(P)** | 6.000 req/s (10%) **(P)** |
| Perfil | CPU-bound (GZIP, JSON, filtro, métricas) | I/O-bound (p50 220 ms por chamada) e limitado pela **cota** |
| Task | **2 vCPU / 4 GB** (heap 3 GB, G1) **(E)** | **1 vCPU / 2 GB** (heap 1,5 GB) **(E)** |
| RPS-alvo por task | **550 req/s** (≈ 60% da CPU) **(E)** | **~28 consultas/s** admitidas (cota 500 chamadas/s ÷ 17,8 chamadas/consulta) **(E)** |
| Alvo `ALBRequestCountPerTarget` (por minuto) | **33.000** (550 × 60) | **1.500** (25 × 60) |
| Tasks no pico | ~99 **(E)** | 3 (fixo pela cota) |
| min / max tasks | **12 / 150** | **2 / 3** (`max` = `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS`) |
| Cooldown out / in | 60 s / 300 s | 60 s / 600 s |
| Deploy | 200% / 100% | **100% / 50%** |
| Política extra | CPU média 60% | — |
| Vazão real da frota | 54.000 req/s com ~99 tasks | **~84 consultas/s** com a cota de 1.500 chamadas/s (mix); ~250–295/s só OPERACAO |
| Cota de mainframe para os 6.000 req/s | — | **~107 mil chamadas/s** nominal, **~128 mil** com 20% de margem (71× a cota atual) **(E)** |

Ajustes recomendados em resiliência (detalhes na seção 5):
- **Visão consolidada:** o rate limiter `dynamodb` cai de 3.000 para **~1.000 req/s por task**. Hoje ele está acima do que a CPU de uma task de 2 vCPU entrega.
- **Fonte primária, já validados:**
  - bulkhead por custódia de **120**;
  - `fontePrimaria-cliente-conta` de **160**;
  - pool de **240**, com `max-conexoes-por-rota` também em **240** (um host só; já está assim no `application.yml` novo).
- **Fonte primária, a mudar:**
  - `max-chamadas-simultaneas` fica em **16**, mas com um **teto de `limite_operacoes` de ~50** nessa rota;
  - rate limiter de **50 a cada 100 ms**, em vez de 500 a cada 1 s;
  - retry de **2 tentativas** (hoje 3); o `prazo-minimo-restante` de **600 ms** previsto está adequado (≥ p99 de uma tentativa);
  - circuito por janela de **tempo de 10 s**.
- **Infraestrutura:**
  - **DynamoDB:** cerca de 150 mil RRU/s, e até 500 mil com o Valkey fora. É preciso aumentar a cota da tabela e usar warm throughput.
  - **Valkey:** cerca de 54 mil `MGET`/s e 262 mil `SET`/s, ~10 Gbit/s. É preciso **cluster mode** ou Serverless, e revisar a premissa do ADR-0011.
  - **API Gateway:** throttle de conta e timeout da integração (seção 6).

## 1. Premissas

| Premissa | Valor | Tipo |
| --- | --- | --- |
| Demanda total | 60.000 req/s: 54.000 na visão consolidada, 6.000 na fonte primária | (P) |
| Mix por tipo, nas duas rotas | 50% OPERACAO, 25% CLIENTE, 25% CONTA | (P) |
| Blocos por consulta (`expand`) | 4: `operacao` + `originacao` + 2. Dos 2 extras, 1 é bloco-lista com média de 2 páginas (1 a 3) | (P) |
| Operações por cliente/conta | 5 em média, no máximo 30 por custódia (1 página por lista). `limite_operacoes` vai até 500 | (P) |
| Latência do mainframe por chamada | p50 220 ms, p99 500 ms → lognormal σ = 0,353, **média 234 ms** | (P, dado) |
| Timeout por tentativa no mainframe | 700 ms | (P, dado) |
| DynamoDB | p50 5 ms, p99 20 ms | (P) |
| Valkey | ~1 ms | (P) |
| Acerto do cache de blocos (Valkey) | 60% das chaves | (P) |
| Acerto do cache de custódia | 90% na visão consolidada, 70% na fonte primária (TTL 24 h, compartilhado) | (P) |
| Retries com o mainframe saudável | +2% de chamadas | (P) |
| Tamanho de um item do DynamoDB (`texto_detalhe` em GZIP) | ≤ 4 KB → 0,5 RRU por leitura eventualmente consistente | (P) |
| Custo de CPU por requisição na visão consolidada | OPERACAO 1,2 ms, CLIENTE/CONTA 3,0 ms (mix 2,1 ms), com tracing a 100% e evento de consumo | (P), **medir** |
| Um vCPU do Fargate | 1 hyperthread x86; utilização-alvo de 60% | (P) |

Lei de Little: **L = λ × W**. L é o número de itens em andamento (requisições, chamadas, conexões), λ é a vazão e W é o tempo de cada um.

## 2. Fonte primária: chamadas ao mainframe e cota

### 2.1 Chamadas por consulta

Cada página e cada tentativa é uma chamada e consome a cota (ADR-0014, ADR-0016).

**OPERACAO, com 4 blocos**

| Passo | Custódia em cache | Sem cache de custódia |
| --- | --- | --- |
| Descoberta: bloco `operacao` | 1 (na custódia lembrada) | 4 (fan-out nas 4 custódias) |
| `originacao` | 1 | 1 |
| Bloco-lista (ex.: `parcelas`, 1 a 3 páginas) | 2 | 2 |
| Bloco-objeto (ex.: `saldo`) | 1 | 1 |
| **Total** | **5** | **8** |

Com 70% de acerto do cache de custódia **(P)**: 5 + 3 × 0,3 = **5,9 chamadas (E)**. Com 95% de acerto, seriam 5,15.

**CLIENTE/CONTA, com N operações e 4 blocos**

A custódia de cada operação vem da própria lista, então não há descoberta.

```
chamadas = 4 listas × páginas por custódia  +  N × (operacao 1 + originacao 1 + lista 2 + objeto 1)
         = 4 × ⌈n_custódia / 30⌉            +  5N
```

| N | 1 | 5 (P) | 10 | 20 | 50 | 100 | 500 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Chamadas | 9 | **29** | 54 | 104 | 254 | 504 | ~2.520 |

As listas não vêm ordenadas: todas as páginas das 4 custódias são lidas antes de cortar em `limite_operacoes`. O limite reduz o detalhamento, mas não as listas.

**Mix**

0,5 × 5,9 + 0,5 × 29 = 17,45 chamadas por consulta. Com 2% de retry, **17,8 chamadas por consulta (E)**. O CLIENTE/CONTA responde por **83% das chamadas**.

### 2.2 Cota necessária × cota atual

| | Chamadas/s |
| --- | --- |
| OPERACAO (3.000/s × 5,9 × 1,02) | 18.054 |
| CLIENTE + CONTA (3.000/s × 29 × 1,02) | 88.740 |
| **Total para 6.000 consultas/s** | **≈ 106.800 (E)** |
| Com 20% de margem (picos, retries em incidente, páginas acima da média) | **≈ 128.000 (E)** |
| Cota atual | 1.500 |

O que 1.500 chamadas/s sustentam **(E)**:

| Tráfego | Consultas/s |
| --- | --- |
| Só OPERACAO, custódia sempre em cache (5 chamadas) | ~294. É a conta das ~300/s, mas ela só vale para OPERACAO |
| Só OPERACAO, 70% de acerto de custódia | ~249 |
| Só CLIENTE/CONTA com 5 operações | ~51 |
| **Mix 50/25/25** | **~84** |

Com a cota atual, **~98,6% dos 6.000 req/s da rota mainframe seriam 429**.

Para o pedido de aumento, há três caminhos, que podem ser combinados:
- **Pedir ~110 mil a 130 mil chamadas/s** (71× a cota atual).
- **Reduzir chamadas por consulta:**
  - menos blocos no `expand` dessa rota;
  - teto de `limite_operacoes` (seção 2.5);
  - acerto do cache de custódia perto de 100%. Ele é compartilhado com a visão consolidada, que o aquece.

  Cada operação a menos por cliente economiza 5 chamadas.
- **Mover CLIENTE/CONTA para a visão consolidada.** A sensibilidade abaixo mostra que é ali que está o custo.

| Cota necessária para 6.000/s (sem margem) | 3 operações/cliente | 5 operações/cliente | 10 operações/cliente |
| --- | --- | --- | --- |
| Expand com 2 blocos (`operacao`+`originacao`) | ~39 mil | ~52 mil | ~82 mil |
| Expand com 4 blocos | ~76 mil | **~107 mil** | ~183 mil |

### 2.3 Latência por consulta

Monte Carlo com a lognormal da seção 1, teto de 700 ms por tentativa e sem retry **(E)**:

| Consulta | p50 | p99 | Prazo |
| --- | --- | --- | --- |
| OPERACAO, custódia em cache, lista com 2 páginas | 0,69 s | 1,13 s | 2,5 s |
| OPERACAO, sem cache de custódia, lista com 3 páginas | 1,01 s | 1,50 s | 2,5 s |
| CLIENTE, 5 operações (16 simultâneas) | 1,00 s | 1,38 s | 6 s |
| CLIENTE, 50 operações (16 simultâneas) | 4,30 s | 4,72 s | 6 s |
| CLIENTE, 75 operações (16 simultâneas) | 6,13 s | **6,57 s → 504** | 6 s |
| Lista de 500 operações numa só custódia (17 páginas sequenciais) | 3,96 s | 4,85 s | — (só a lista) |

O pior caso da OPERACAO, com 1,5 s, ainda deixa espaço para um retry de 700 ms dentro dos 2,5 s.

### 2.4 Concorrência (Little) e dimensionamento por task

Com 3 tasks e a cota de 1.500 chamadas/s, cada task faz **500 chamadas/s** (= `cota ÷ max_tasks`).

| Chamadas em andamento por task | Média (234 ms) | Mainframe em p99 (500 ms) | Tudo no timeout (700 ms) |
| --- | --- | --- | --- |
| Total (500/s) | **117** | 250 | 350 |
| CLIENTE/CONTA (83% = 415/s) | 97 | 208 | 290 |
| Maior custódia, supondo 40% das chamadas (200/s) | 47 | 100 | 140 |

Validação dos valores que vão para o `application.yml` **(E)**:

- **Pool HTTP de 240 conexões.** Sustenta a cota inteira da task até uma latência média de 240 ÷ 500 = **480 ms**, perto do p99. Acima disso, o pool (e não o mainframe) passa a ser o limite, o que é a contrapressão desejada.
  - **`max-conexoes-por-rota` também precisa ser 240.** Todas as chamadas vão para o mesmo host do gateway, e com 50 o pool efetivo seria 50.
  - Regra sugerida, a validar na subida como já se faz no DynamoDB (`DynamoDbConfig`): `pool ≥ max(cota_por_task × 0,48 s, 2 × bulkhead_por_custódia)`.
- **Bulkhead por custódia de 120.** Na operação normal, a maior custódia usa ~47 vagas, e ~100 com o mainframe em p99.
  - Uma custódia degradada (tudo no timeout) ocupa no máximo 120, metade do pool, e as outras três continuam com 120 conexões.
  - Só duas custódias degradadas ao mesmo tempo esgotam o pool. Esse risco é aceito: a fila de conexão (`timeout-obter-conexao` 50 ms) vira 503 rápido.
- **`fontePrimaria-cliente-conta` de 160.** O CLIENTE/CONTA usa ~97 vagas em média e ~208 em p99. Ele só passa a limitar com o mainframe degradado, que é quando deve limitar, e sempre sobram ≥ 80 conexões do pool para OPERACAO.
- **Rate limiter de 500/s por task.** Hoje são `limit-for-period: 2000` a cada `1s`. Recomendação: **50 a cada 100 ms**, mesma vazão.
  - As 3 tasks renovam os períodos em instantes diferentes. Com períodos de 1 s, uma janela deslizante de 1 s pode ver até 2 × 500 por task, ou 3.000 na frota, se o mainframe medir a cota assim.
  - Com 100 ms o excesso cai para ≤ 10%, e a carga chega ao mainframe sem rajadas no início de cada segundo.

**Desperdício de cota.** A recusa acontece por chamada, no meio da consulta:
- um CLIENTE que já gastou 20 das 29 chamadas e recebe 429 perde as 20;
- perto da cota, a vazão útil cai abaixo dos ~84/s.

Recomendação (mudança de código, a decidir): admitir pela consulta. Uma forma é um rate limiter de consultas/s na entrada da rota, calibrado em `cota_por_task ÷ chamadas_médias`, com o de chamadas mantido como teto rígido. Outra é reservar as permissões estimadas logo depois da lista de operações.

### 2.5 `MAX_CHAMADAS_SIMULTANEAS` (item 5)

O semáforo vale por requisição CLIENTE/CONTA. O tempo do detalhamento é ~ `chamadas × 234 ms ÷ simultâneas`, mais as cadeias de páginas. Simulação do p99 da consulta inteira (lista + detalhamento) contra o prazo de 6 s **(E)**:

| Operações (N) | Chamadas | 16 simultâneas | 32 simultâneas | 64 simultâneas |
| --- | --- | --- | --- | --- |
| 5 | 29 | 1,38 s | 1,32 s | 1,32 s |
| 20 | 104 | 2,52 s | 1,85 s | 1,51 s |
| 50 | 254 | **4,72 s** | 2,92 s | 2,05 s |
| 75 | 379 | 6,57 s ✗ | 3,86 s | 2,50 s |
| 100 | 504 | 8,44 s ✗ | **4,77 s** | 2,97 s |
| 200 | 1.008 | 16,2 s ✗ | 8,75 s ✗ | 5,09 s |
| 500 | ~2.520 | 39 s ✗ | 20,6 s ✗ | 11,4 s ✗ |

Conclusões:
- **16 é suficiente para o caso típico** (5 operações: 1,4 s) e cabe no prazo até ~60 operações.
- **Para 500 operações, nenhum valor razoável resolve.** Seriam necessárias ~130 simultâneas, e a consulta custaria ~2.520 chamadas: 5 s da cota de uma task, ou 1,7 s da cota da frota inteira. Um único cliente grande derrubaria todos os outros com 429.

  O limite é a **cota**, não a concorrência. Subir o semáforo só faz cada consulta grande gastar a cota mais depressa.
- **Recomendação:**
  - Manter **16**, que é também o `permitted-number-of-calls-in-half-open-state` (ADR-0009).
  - Criar um **teto de `limite_operacoes` na rota fonte primária: ~50 (P), configurável.** Acima disso, 400 com Problem Details sugerindo a visão consolidada. Alternativa: um orçamento de chamadas por consulta (~300), verificado depois da lista.
  - Se o negócio exigir 100 operações nessa rota, subir para **32** e o meio-aberto junto, mantendo `fontePrimaria-cliente-conta` ≥ 5 × 32.
- **Encaixe com o bulkhead.** Com 16 por consulta, o bulkhead `fontePrimaria-cliente-conta` de 160 comporta 10 consultas grandes em fan-out pleno. A carga típica é ~14 consultas CLIENTE/CONTA em andamento por task (14/s × ~1 s), cada uma com ~7 chamadas em média, ou ~97 vagas. Cabe.

### 2.6 Circuit breaker e retry por custódia × bloco

São 44 instâncias por task (4 custódias × 9 blocos + 2 listas). Cada task divide 500 chamadas/s entre elas:
- uma instância quente (ex.: `fontePrimaria-SF-operacao`) recebe ~25 a 50 chamadas/s;
- uma fria (ex.: `fontePrimaria-OD-controles_iof`) recebe ≤ 1/s **(E)**.

**Circuit breaker**
- **Hoje:** `COUNT_BASED` de 50 chamadas, com mínimo de 20. A instância quente tem uma janela de 1 a 2 s e abre com uma rajada curta. A fria precisa de 20 a 50 s para decidir.
- **Recomendação:** `sliding-window-type: TIME_BASED`, `sliding-window-size: 10` (s), `minimum-number-of-calls: 10`. A taxa de falha de 50% e a espera de 5 s em aberto continuam. Assim todas as instâncias decidem na mesma escala de tempo.
- O timeout de 700 ms já faz a chamada lenta virar falha. Uma `slow-call-duration-threshold` de 600 ms com taxa de 80% abriria o circuito antes dos timeouts, numa degradação sistêmica (opcional).

**Retry**
- **`max-attempts` de 3 para 2.** Cada tentativa consome um token da cota (ADR-0014). Num incidente, 3 tentativas triplicam o consumo justamente quando o mainframe está mal, e as outras custódias e blocos recebem 429.
- **`prazo-minimo-restante` da fonte primária de 50 ms para 500 a 600 ms (≥ p99 de uma tentativa; o `application.yml` novo usa 600 ms).** Com 50 ms, o retry começa sem chance de terminar, gasta um token e vira 504 de qualquer forma.
  - O valor de 50 ms foi calculado para o prazo antigo de 150 ms (ADR-0006).
  - Se a propriedade for única para as duas rotas, ela precisa ficar por rota.
- **429 do próprio mainframe.** Ele indica cota global estourada (deploy fora do padrão, outro consumidor). Considere não retentar na mesma requisição, ou retentar só com backoff ≥ 200 ms. Retentar na hora agrava o estouro.

### 2.7 Task, escala e borda do serviço `fonte-primaria`

- **1 vCPU / 2 GB (E).** A CPU com a vazão da cota é pequena: ~500 chamadas/s × ~0,3 ms (parse do trancode + HTTP) **(P)** + ~28 consultas/s × ~2 ms ≈ **0,2 vCPU**.
  - Não se usa 0,5 vCPU por três motivos: a subida do Spring Boot fica 2× mais lenta, o 429 também gasta CPU (abaixo) e a economia é de centavos por hora (3 tasks).
  - Com 1 CPU a JVM escolhe o SerialGC. Com o heap de 1,5 GB e prazos de segundos, as pausas (dezenas de ms) não importam.
  - Memória: 240 chamadas em andamento com ≤ 30 ocorrências cada ocupam poucos MB. 2 GB é o mínimo do Fargate para 1 vCPU.
- **min 2 / max 3.** O `max` é o divisor da cota e entra na task como `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` (o `infra/ecs` usa a mesma variável para os dois). Com 2 tasks a frota tem 1.000 chamadas/s; é a capacidade parada aceita no ADR-0015.
- **Alvo de 1.500 requisições/min por task (25 req/s).** Com qualquer demanda perto da cota, o serviço fica no máximo, como esperado.
- **Deploy 100% / 50%.** Com 3 tasks, para uma, sobe a substituta e segue. A cota nunca é excedida.
- **O 429 precisa sair na borda.** Com 6.000 req/s de demanda e ~84 admitidas, cada uma das 3 tasks recusaria ~2.000 req/s.
  - Mesmo barato (~0,3 a 0,5 ms de CPU com o evento de consumo e o tracing **(P)**), isso ocupa ~0,6 a 1 vCPU só para recusar, e a task de 1 vCPU satura.
  - **Recomendação:** throttle por método no API Gateway para `GET /v1/operacoes-credito/fonte-primaria` (`aws_api_gateway_method_settings`, `throttling_rate_limit`) em ~100 a 120 req/s, perto da capacidade admitida mais uma margem. O 429 sai do gateway e as tasks só veem o que conseguem atender.
  - Sem isso, use 2 vCPU / 4 GB nessa rota e meça o custo do 429 (`carga/README.md`, passo 6).
- **Cenário com a cota aumentada para ~128 mil chamadas/s (E).**
  - Cerca de 2.000 chamadas/s por task de 1 vCPU (~60% da CPU), ou **~64 tasks** (`COTA_MAX_TASKS` = 64).
  - Por task: pool de ~960, bulkhead por custódia de ~480 e `fontePrimaria-cliente-conta` de ~640, pela mesma regra da seção 2.4.
  - O máximo fixo passa a custar 64 tasks paradas fora do pico. Nesse cenário vale a evolução do ADR-0015: cota ajustada pela contagem de tasks em execução.

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

Leituras eventualmente consistentes. Itens ≤ 4 KB custam 0,5 RRU cada **(P)**. Uma chave pedida no `BatchGetItem` e inexistente também consome o mínimo de 0,5 RRU **(P, conservador)**. Isso torna caro o pedido nas 4 custódias quando a custódia não está em cache (ADR-0012).

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

## 4. Fonte primária e visão consolidada isoladas

- **Limitação.** A cota do mainframe (rate limiter `fontePrimaria`, 429) roda em outro serviço ECS e nunca recusa chamadas da visão consolidada. Bulkheads, pool e circuitos também são separados (ADR-0014/0015).
- **O que os dois serviços compartilham:**
  - o **Valkey**, para a chave `custodia:<id>`. O CB `valkey` e o fail-open protegem a fonte primária de um Valkey lento;
  - o **ALB**.

## 5. Ajustes recomendados no `application.yml`

| Propriedade | Valor previsto | Recomendação | Motivo |
| --- | --- | --- | --- |
| `consultas.fonte-primaria.pool.max-conexoes` | 240 | **240** ✓ | Cota da task × 480 ms (§2.4) |
| `consultas.fonte-primaria.pool.max-conexoes-por-rota` | 240 | **240** ✓ | Um host só: o limite por rota é o pool efetivo |
| bulkhead `fontePrimaria-<CUSTODIA>` | 120 | **120** ✓ | Metade do pool; normal ~47, p99 ~100 |
| bulkhead `fontePrimaria-cliente-conta` | 160 | **160** ✓ | Normal ~97; sobram ≥ 80 para OPERACAO |
| `fan-out.max-chamadas-simultaneas` | 16 | **16** + teto de `limite_operacoes` ~50 na fonte primária (ou 32 com teto de 100) | §2.5: o limite é a cota |
| CB `fontePrimaria` `permitted-number-of-calls-in-half-open-state` | 16 | = `max-chamadas-simultaneas` | ADR-0009 |
| CB `fontePrimaria` janela | COUNT_BASED 50 / mín. 20 | **TIME_BASED 10 s / mín. 10** | Instâncias quentes e frias na mesma escala de tempo (§2.6) |
| retry `fontePrimaria` `max-attempts` | 3 | **2** | Cada tentativa consome a cota |
| `consultas.fonte-primaria.retry.prazo-minimo-restante` | 600 ms | **500 a 600 ms** ✓ | ≥ p99 de uma tentativa; prazo novo de 2,5 s/6 s |
| ratelimiter `fontePrimaria` | cota ÷ max-tasks por 1 s | **(cota ÷ max-tasks) ÷ 10 por 100 ms** | Sem rajadas, sem 2× na janela deslizante |
| ratelimiter `dynamodb` | 3.000 / 1 s | **~1.000 / 1 s** (ou 100 / 100 ms), recalibrar com o k6 | §3.4: 3.000 nunca dispara antes da CPU |
| bulkheads `dynamodb` / `dynamodb-cliente-conta`, pool DynamoDB | 180 / 40 / 300 | **mantidos** ✓ | §3.4 |

## 6. Limites da plataforma fora da aplicação

- **API Gateway (`infra/api-gateway`):**
  - **Throttle de conta:** o padrão é **10.000 req/s** por região, contra 60.000 de demanda. É preciso pedir o aumento, além de um Usage Plan coerente por consumidor.
  - **Timeout da integração:** `integration_timeout_ms` tem padrão de 1.000 ms, abaixo dos prazos da fonte primária (2,5 s e 6 s). O gateway cortaria antes da aplicação. A rota fonte primária precisa de **≥ 6.500 ms**.
  - **Throttle por método na rota fonte primária** (§2.7).
  - **Custo:** um REST API a 60 mil req/s é um custo relevante (cobrado por milhão de requisições); vale comparar com HTTP API ou com o acesso direto ao ALB.
- **ALB.**
  - Escala sozinho, mas um degrau súbito até 60 mil req/s pede reserva de LCU.
  - A ~10 KB por resposta, são ~2 TB/h processados, ou ~2.000 LCU/h só de banda.
- **Fargate:** a cota de vCPU On-Demand da conta precisa cobrir o pico (150 × 2 + 3 × 1 ≈ 300 vCPU) e o deploy a 200% (~600 vCPU).
- **SQS** (evento de consumo): ~6.000 `SendMessageBatch`/s, sem limite na fila standard, mas com custo próprio por requisição.
- **OTel Collector.** Com o tracing a 100%, recebe os spans de 60 mil req/s para fazer o tail sampling. Ele precisa ser dimensionado à parte; o ponto fixo do k6 com o Collector ligado mostra o custo do lado da aplicação.

## 7. Como validar com o k6

Procedimento completo em `carga/README.md`: limitar o container a 2 vCPU / 4 GB ou 1 vCPU / 2 GB, latência do mainframe no WireMock, rampa até o joelho e extrapolação para o Fargate.

| Número | Como medir | Medido (M) |
| --- | --- | --- |
| CPU por requisição OPERACAO / CLIENTE / CONTA (P: 1,2 / 3,0 ms) | `MIX_*` com um tipo só; CPU (núcleos) × 1000 ÷ RPS; com o cache ligado e desligado | |
| RPS-alvo por task da visão consolidada (E: 550) | Joelho da rampa (`MODO=rampa`) × 0,7, convertido pelo fator do Fargate; confirmar com 1 task na AWS | |
| Joelho e saturação (E: 760 / 950) | Rampa: último degrau com os thresholds verdes; CPU no limite | |
| Chamadas ao mainframe por consulta (E: 5,9 / 29) | Painel "Chamadas ao mainframe × cota" ÷ RPS, por tipo (`MIX_*`) | |
| Chamadas em andamento e vagas nos bulkheads (E: 117 por task) | Painéis de bulkhead com a latência lognormal no WireMock e `COTA_MAX_TASKS=1` | |
| Custo do 429 (P: 0,3 a 0,5 ms) | `fonte-primaria.js` com `RPS=150`, `MAX_429=0.9`; CPU ÷ (429/s) | |
| p99 da fonte primária por N operações (§2.5) | `LIMITE_OPERACOES` e CPFs com mais operações; ver `duracao_200{tipo_consulta:CLIENTE}` | |
| Acerto do cache de blocos e de custódia (P: 60% / 90%) | Produção: `consultas.cache{resultado}` e `consultas_custodia_descoberta_total{resultado="cache"}` | |
| RRU/s do DynamoDB (E: 150 mil) | Produção/homologação: `ConsumedReadCapacityUnits` da tabela e do GSI ÷ req/s | |
| Valkey (E: 54 mil `MGET`/s, 262 mil `SET`/s) | `GetTypeCmds`/`SetTypeCmds`, `NetworkBytesIn/Out` e `EngineCPUUtilization` no ElastiCache | |
