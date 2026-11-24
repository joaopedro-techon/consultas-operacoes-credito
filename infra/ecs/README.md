# Serviço ECS Fargate (referência) — IaC

Terraform de referência do serviço `visao-consolidada`, atrás de um ALB que já existe, com seu target group e seu autoscaling. O serviço `fonte-primaria` (mainframe), com a regra e o target group dele, fica no repositório `consultas-operacoes-credito-mainframe` (ADR-0017). Os números padrão saem de [`docs/capacidade-ecs-fargate.md`](../../docs/capacidade-ecs-fargate.md) e são **estimativas**: recalibre com o k6 (`carga/README.md`) antes de produção.

## O que é criado

| Recurso | `visao-consolidada` |
| --- | --- |
| Task definition | 2 vCPU / 4 GB |
| Target group (`ip`, HTTP 8080) | round robin + slow start 60 s |
| Health check | `/actuator/health/readiness`, 10 s, 2 ok / 3 falhas |
| Regra no listener (path) | `/v1/operacoes-credito*` (prioridade `prioridade_regras + 1`; `prioridade_regras` fica para a regra mais específica da fonte primária, criada pelo outro repositório) |
| Serviço ECS | deploy 200% / 100%, circuit breaker com rollback |
| Autoscaling | 12–150 tasks; `ALBRequestCountPerTarget` alvo 33.000/min (550 req/s); CPU 60% |
| Log group | `/ecs/<nome>/visao-consolidada` |

Pontos de projeto:
- **`ALBRequestCountPerTarget` é a soma por minuto**: o alvo é RPS por task × 60.
- Há uma segunda política por CPU: se o custo por requisição for maior que o estimado, a CPU escala antes de a latência degradar. O ECS sobe se qualquer política pedir e só desce se todas permitirem.
- Sistema de arquivos da task somente leitura; `/tmp` (Tomcat, hsperfdata) fica num volume efêmero.
- `stopTimeout` 45 s > desligamento gracioso (30 s) > `deregistration_delay` (30 s) > maior prazo (400 ms).
- Os recursos continuam em `for_each` sobre um mapa de uma entrada, para que os endereços no state (`aws_ecs_service.this["visao-consolidada"]` etc.) sejam os mesmos de antes da separação.

## State de antes da separação

Se este módulo já foi aplicado com os dois serviços, o state ainda tem os recursos `["fonte-primaria"]`, e um `apply` desta versão os **destruiria**. Antes do primeiro `plan`, tire-os deste state (ou mova-os para o state do repositório do mainframe com `terraform state mv -state-out=...`), seguindo o runbook de cutover do repositório do mainframe:

Primeiro, liste o que o state tem do serviço:

```powershell
terraform state list | Select-String 'fonte-primaria'
```

Em seguida, remova cada endereço listado, um por linha. O `--%` passa o resto da linha ao `terraform` sem que o PowerShell 5.1 tire as aspas internas; sem ele, o `terraform` recusa o endereço.

```powershell
terraform --% state rm aws_cloudwatch_log_group.this[\"fonte-primaria\"]
terraform --% state rm aws_ecs_task_definition.this[\"fonte-primaria\"]
terraform --% state rm aws_lb_target_group.this[\"fonte-primaria\"]
terraform --% state rm aws_lb_listener_rule.this[\"fonte-primaria\"]
terraform --% state rm aws_ecs_service.this[\"fonte-primaria\"]
terraform --% state rm aws_appautoscaling_target.this[\"fonte-primaria\"]
terraform --% state rm aws_appautoscaling_policy.requisicoes[\"fonte-primaria\"]
# só se o serviço tinha alvo_cpu (a política de CPU é opcional)
terraform --% state rm aws_appautoscaling_policy.cpu[\"fonte-primaria\"]
```

Depois disso, `terraform state list | Select-String 'fonte-primaria'` não pode listar nada, e o `plan` seguinte não pode ter nenhum `destroy`.

## Fora do escopo (pré-requisitos)

Cluster ECS, VPC/subnets, ALB e listener HTTPS, security groups (tasks aceitando 8080 vindo do ALB), roles IAM (execução: ECR, logs e segredos; aplicação: DynamoDB `GetItem`/`BatchGetItem`/`Query` e SQS `SendMessage`), ElastiCache, fila SQS e Collector OTel. O módulo recebe tudo por variável.

## Aplicar

```powershell
cd infra/ecs
Copy-Item terraform.tfvars.example terraform.tfvars   # git-ignored; ajuste ARNs, imagem e ambiente
Copy-Item backend.hcl.example backend.hcl             # git-ignored; ajuste o bucket (a chave já é a deste repositório)
terraform init -backend-config=backend.hcl
terraform plan -out tfplan
terraform apply tfplan
terraform output
```

**State.** O state fica em S3 (`backend "s3" {}` em `versions.tf`, com lock nativo `use_lockfile`), na chave `consultas-operacoes-credito/ecs/terraform.tfstate`. O repositório do mainframe usa a chave dele (`consultas-operacoes-credito-mainframe/ecs/...`). **Nunca aponte um repositório para a chave do outro**: os nomes de recurso são iguais, e um `apply` com o state errado destruiria o serviço do outro repositório. Se você já tem um state local de antes, leve-o para o S3 com `terraform init -backend-config=backend.hcl -migrate-state` e só então faça o `state rm` da seção "State de antes da separação". Com o `backend "s3"` declarado, o Terraform não roda comandos de state antes do `init`. O que importa é que nenhum `plan`/`apply` aconteça antes do `state rm`.

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
