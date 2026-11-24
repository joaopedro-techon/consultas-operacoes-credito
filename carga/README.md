# Testes de carga (k6)

Cenários k6 para medir quanto **uma task** da visão consolidada aguenta e validar as estimativas de [`docs/capacidade-ecs-fargate.md`](../docs/capacidade-ecs-fargate.md). Os testes rodam contra o docker-compose local (aplicação em `http://localhost:8080`), com a aplicação limitada à CPU e à memória da task Fargate.

| Script | Rota | Padrão |
| --- | --- | --- |
| `k6/visao-consolidada.js` | `GET /v1/operacoes-credito` (DynamoDB + Valkey) | 300 req/s, mix 50% OPERACAO / 25% CLIENTE / 25% CONTA |

Todos os cenários usam chegada aberta (`constant-arrival-rate`, ou `ramping-arrival-rate` com `MODO=rampa`): a taxa não cai quando a aplicação fica lenta, como acontece em produção. A massa é a de `localstack/massa`: operações 100000001..100000007 e 100000101..100000114, CPF/CNPJ 11222333000181, 52998224725, 12ABC34501DE35 e 11144477735, e as contas de `indices.env`. O `expand` sorteia 2 blocos além de `operacao` e `originacao` (cerca de 4 por consulta, premissa do modelo).

## Variáveis

| Variável | Padrão | Uso |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Aplicação |
| `RPS` | 300 | Taxa total (somando os três tipos); na rampa, a taxa final |
| `MODO` | `constante` | `constante` ou `rampa` |
| `DURACAO` | `2m` | Duração no modo constante |
| `RPS_INICIAL`, `DEGRAUS`, `DURACAO_DEGRAU` | `RPS/DEGRAUS`, 6, `1m` | Rampa |
| `MIX_OPERACAO`, `MIX_CLIENTE`, `MIX_CONTA` | 0.5, 0.25, 0.25 | Fatia de cada tipo (0 desliga o cenário) |
| `LIMITE_OPERACOES` | (ausente) | `limite_operacoes` de CLIENTE/CONTA |
| `VUS`, `MAX_VUS` | 200, 2000 | VUs pré-alocados e máximo, divididos pelo mix |
| `CONSUMIDOR` | `carga-k6` | Header `x-consumidor` |

Métricas próprias: `duracao_200` (latência só das respostas 200, com a tag `tipo_consulta`, usada nos thresholds), `respostas_429` e `taxa_429` (fora de `http_req_failed`; nesta rota, nenhum 429 é esperado), `respostas_parciais` (200 com `x-blocos-indisponiveis`) e `respostas_5xx`.

Thresholds: p99 OPERACAO < 80 ms, CLIENTE/CONTA < 200 ms; erros < 1%; nenhum 429; nenhuma iteração descartada.

## 1. Subir a stack com os limites da task

Na raiz do repositório, com o override `carga/docker-compose.carga.yml`:

```powershell
# Task de 2 vCPU / 4 GB
$env:APP_CPUS = "2"; $env:APP_MEMORIA = "4g"
docker compose -f docker-compose.yml -f carga/docker-compose.carga.yml up -d --build
```

Sem compose, o equivalente para a aplicação já construída é `docker run --cpus=2 --memory=4g ...`. A JVM respeita esses limites: `ActiveProcessorCount` sai do `--cpus` e o heap fica em 75% da memória (`-XX:MaxRAMPercentage=75` no `Dockerfile`), como no Fargate. Com 1 CPU a JVM escolhe o SerialGC, como faria numa task de 1 vCPU.

Para a aplicação não disputar núcleos com LocalStack, Valkey e o próprio k6, fixe os núcleos dela e deixe os outros para o resto:

```powershell
docker update --cpuset-cpus 0,1 consultas-operacoes-credito-app-1
```

## 2. Rodar

```powershell
# Ponto fixo
k6 run -e RPS=500 -e DURACAO=3m carga/k6/visao-consolidada.js
# Rampa para achar o joelho (6 degraus de 1 min até 1.500 req/s)
k6 run -e MODO=rampa -e RPS=1500 carga/k6/visao-consolidada.js
```

Rode o k6 de outra máquina, ou em núcleos que não sejam os da aplicação. Acompanhe no Grafana (http://localhost:3000): latência, status, cache, bulkheads e rate limiters.

## 3. Medir o RPS máximo por task

1. **Aqueça** por 1–2 minutos a ~30% da taxa esperada (JIT e pools). Descarte esse trecho.
2. **Faça a rampa** e anote o último degrau em que todos os thresholds passam. O joelho aparece como:
   - p99 subindo rápido;
   - `dropped_iterations > 0` (faltou VU porque as respostas demoraram);
   - CPU do container perto do limite (`docker stats`, ou no Prometheus `process_cpu_usage * system_cpu_count`);
   - 503 de bulkhead/rate limiter, ou 504.
3. **Confirme no ponto fixo**: 5 minutos a 100% e depois a 70% do joelho. O **RPS-alvo por task** (o que entra no autoscaling) é cerca de 70% do joelho, para que a task absorva o crescimento da carga enquanto o autoscaling reage.
4. **Calcule o custo de CPU por requisição**, que é o número que se leva para o Fargate:

   ```
   ms de CPU por requisição = uso de CPU (em núcleos) × 1000 / RPS
   ex.: 1,3 núcleo a 600 req/s → 2,2 ms/req
   ```

5. **Cache.** A massa local é pequena e, depois do aquecimento, o acerto do cache fica perto de 100%, acima da premissa de produção (60%). Meça duas vezes, com `CONSULTAS_CACHE_HABILITADO=true` e `false`, e interpole o custo de CPU por requisição para o acerto real (a taxa observada sai da métrica `consultas.cache`, tag `resultado`, ADR-0011).

## 4. Extrapolar para o Fargate

Um vCPU do Fargate é um hyperthread de um processador de servidor e costuma ser **mais lento** que um núcleo de desktop recente. Use um fator de conversão (premissa: 1,3) até medir na AWS:

```
RPS-alvo por task ≈ (vCPU da task × 1000 × 0,6) / (ms de CPU por requisição medido × 1,3)
ex.: 2 vCPU, 1,6 ms/req local → 2000 × 0,6 / (1,6 × 1,3) ≈ 577 req/s
alvo do ALBRequestCountPerTarget = RPS-alvo × 60
tasks no pico = RPS do pico ÷ RPS-alvo
```

O LocalStack é muito mais lento que o DynamoDB e roda na mesma máquina. Por isso, a latência medida localmente **não vale** para produção, só o custo de CPU da aplicação. A validação final é em uma conta AWS:
1. Suba uma única task (`min_tasks = max_tasks = 1` no `infra/ecs`).
2. Rode o k6 de uma EC2 na mesma VPC, contra o ALB, com a mesma rampa.
3. Ajuste `alvo_requisicoes_por_task_minuto`, `min_tasks`/`max_tasks` e o rate limiter `dynamodb` com o joelho medido.
4. Registre os números no documento de capacidade.
