# Serviços ECS Fargate (referência) — IaC

Terraform de referência dos dois serviços da API (ADR-0015): a mesma imagem roda como `visao-consolidada` e `fonte-primaria`, atrás de um ALB que já existe, cada um com seu target group e seu autoscaling. Os números padrão saem de [`docs/capacidade-ecs-fargate.md`](../../docs/capacidade-ecs-fargate.md) e são **estimativas**: recalibre com o k6 (`carga/README.md`) antes de produção.

## O que é criado

| Recurso | `visao-consolidada` | `fonte-primaria` |
| --- | --- | --- |
| Task definition | 2 vCPU / 4 GB, `CONSULTAS_ROTAS_ATIVAS=visao-consolidada` | 1 vCPU / 2 GB, `CONSULTAS_ROTAS_ATIVAS=fonte-primaria`, `CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO` = cota, `CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS` = `max_tasks` |
| Target group (`ip`, HTTP 8080) | round robin + slow start 60 s | least outstanding requests |
| Health check | `/actuator/health/readiness`, 10 s, 2 ok / 3 falhas | igual |
| Regra no listener (path) | `/v1/operacoes-credito*` (prioridade `prioridade_regras + 1`) | `/v1/operacoes-credito/fonte-primaria*` (prioridade `prioridade_regras`) |
| Serviço ECS | deploy 200% / 100%, circuit breaker com rollback | deploy **100% / 50%**: nunca mais tasks que `max_tasks`, nem durante o deploy |
| Autoscaling | 12–150 tasks; `ALBRequestCountPerTarget` alvo 33.000/min (550 req/s); CPU 60% | 2–3 tasks; `ALBRequestCountPerTarget` alvo 1.500/min (25 req/s) |
| Log group | `/ecs/<nome>/visao-consolidada` | `/ecs/<nome>/fonte-primaria` |

Pontos de projeto:
- **`ALBRequestCountPerTarget` é a soma por minuto**: o alvo é RPS por task × 60.
- **`max_tasks` da fonte primária é também o divisor da cota.** Cada task limita-se a `cota ÷ max_tasks` chamadas/s (output `fonte_primaria_chamadas_por_segundo_por_task`). Subir `max_tasks` sem subir a cota não aumenta a vazão; só divide a mesma cota em fatias menores.
- A visão consolidada tem uma segunda política por CPU: se o custo por requisição for maior que o estimado, a CPU escala antes de a latência degradar. O ECS sobe se qualquer política pedir e só desce se todas permitirem.
- Sistema de arquivos da task somente leitura; `/tmp` (Tomcat, hsperfdata) fica num volume efêmero.
- `stopTimeout` 45 s > desligamento gracioso (30 s) > `deregistration_delay` (30 s) > maior prazo (6 s).

## Fora do escopo (pré-requisitos)

Cluster ECS, VPC/subnets, ALB e listener HTTPS, security groups (tasks aceitando 8080 vindo do ALB), roles IAM (execução: ECR, logs e segredos; aplicação: DynamoDB `GetItem`/`BatchGetItem`/`Query` e SQS `SendMessage`), ElastiCache, fila SQS e Collector OTel. O módulo recebe tudo por variável.

## Aplicar

```powershell
cd infra/ecs
Copy-Item terraform.tfvars.example terraform.tfvars   # git-ignored; ajuste ARNs, imagem e ambiente
terraform init                                         # ou: terraform init -backend-config=backend.hcl
terraform plan -out tfplan
terraform apply tfplan
terraform output
```

Validação local sem credenciais (a mesma da CI):

```powershell
terraform -chdir=infra/ecs fmt -check -recursive
terraform -chdir=infra/ecs init -backend=false -input=false
terraform -chdir=infra/ecs validate
```

## Antes de produção

- **Cotas da conta**: vCPU do Fargate On-Demand na região (150 × 2 vCPU = 300 vCPU no pico, até 600 durante um deploy a 200%); throttle de conta do API Gateway (padrão 10.000 req/s por região, abaixo dos 60.000 req/s); throughput máximo da tabela DynamoDB on-demand (ver o documento de capacidade).
- **Timeout da integração do API Gateway** (`infra/api-gateway`, `integration_timeout_ms`, padrão 1000 ms): a rota fonte primária tem prazos de 2,5 s e 6 s e precisa de pelo menos 6.500 ms, senão o gateway corta antes da aplicação.
- **ALB**: para um degrau súbito até 60.000 req/s, reserve capacidade (LCU reservation) ou peça pre-warm.
- **Graviton** (`arquitetura_cpu = "ARM64"`): cerca de 20% mais barato por vCPU; exige imagem multi-arquitetura e nova medição.
- **`.terraform.lock.hcl`** ainda não versionado: gerar no primeiro `terraform init` e commitar.
