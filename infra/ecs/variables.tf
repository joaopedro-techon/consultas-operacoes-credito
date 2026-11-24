variable "aws_region" {
  description = "Regiao AWS do cluster ECS e do ALB."
  type        = string
}

variable "nome" {
  description = "Prefixo dos recursos (servicos, task definitions, log groups, politicas de escala)."
  type        = string
  default     = "consultas-operacoes-credito"
}

variable "prefixo_target_group" {
  description = "Prefixo curto dos target groups (o nome do target group aceita no maximo 32 caracteres)."
  type        = string
  default     = "consultas"

  validation {
    condition     = can(regex("^[A-Za-z0-9-]{1,28}$", var.prefixo_target_group))
    error_message = "prefixo_target_group aceita letras, digitos e '-', com no maximo 28 caracteres."
  }
}

# ---------------------------------------------------------------------------------------------------------
# Infraestrutura existente (nao criada aqui)
# ---------------------------------------------------------------------------------------------------------

variable "cluster_name" {
  description = "Nome do cluster ECS existente."
  type        = string
}

variable "vpc_id" {
  description = "VPC dos target groups (a mesma das subnets)."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets privadas das tasks (uma por AZ; use 3 AZs)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "Informe ao menos duas subnets (AZs diferentes)."
  }
}

variable "security_group_ids" {
  description = "Security groups das tasks. Devem aceitar TCP 8080 vindo do security group do ALB e permitir saida para DynamoDB, Valkey, SQS, gateway do mainframe e Collector."
  type        = list(string)
}

variable "alb_arn" {
  description = "ARN do Application Load Balancer existente (usado no resource_label do ALBRequestCountPerTarget)."
  type        = string
}

variable "alb_listener_arn" {
  description = "ARN do listener (HTTPS) do ALB onde as regras por path sao criadas."
  type        = string
}

variable "prioridade_regras" {
  description = "Prioridade da regra da fonte primaria no listener; a da visao consolidada usa a seguinte. A regra mais especifica (fonte primaria) precisa vir antes."
  type        = number
  default     = 100

  validation {
    condition     = var.prioridade_regras >= 1 && var.prioridade_regras <= 49999
    error_message = "prioridade_regras deve estar entre 1 e 49999."
  }
}

variable "execution_role_arn" {
  description = "Role de execucao da task (pull da imagem, logs, leitura dos segredos de var.segredos)."
  type        = string
}

variable "task_role_arn" {
  description = "Role da aplicacao (DynamoDB GetItem/BatchGetItem/Query, SQS SendMessage)."
  type        = string
}

# ---------------------------------------------------------------------------------------------------------
# Aplicacao
# ---------------------------------------------------------------------------------------------------------

variable "imagem" {
  description = "Imagem da aplicacao (a mesma nos dois servicos), de preferencia por digest: <repo>@sha256:..."
  type        = string
}

variable "arquitetura_cpu" {
  description = "X86_64 ou ARM64 (Graviton, ~20% mais barato; exige imagem multi-arquitetura e nova medicao com o k6)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.arquitetura_cpu)
    error_message = "arquitetura_cpu deve ser X86_64 ou ARM64."
  }
}

variable "ambiente" {
  description = "Variaveis de ambiente comuns aos dois servicos (AWS_REGION, CONSULTAS_DYNAMODB_TABELA, CONSULTAS_VALKEY_HOST, CONSULTAS_FONTE_PRIMARIA_URL, CONSULTAS_EVENTOS_SQS_URL_FILA, OTEL_EXPORTER_OTLP_ENDPOINT...). CONSULTAS_ROTAS_ATIVAS e a cota sao preenchidas por servico."
  type        = map(string)
  default     = {}

  validation {
    condition     = length(setintersection(keys(var.ambiente), ["CONSULTAS_ROTAS_ATIVAS", "CONSULTAS_FONTE_PRIMARIA_COTA_CHAMADAS_POR_SEGUNDO", "CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS"])) == 0
    error_message = "CONSULTAS_ROTAS_ATIVAS e a cota da fonte primaria sao definidas pelo modulo (var.fonte_primaria), nao em var.ambiente."
  }
}

variable "segredos" {
  description = "Segredos injetados como variavel de ambiente: nome => ARN no Secrets Manager ou SSM (ex.: CONSULTAS_EVENTOS_SEGREDO_HMAC)."
  type        = map(string)
  default     = {}
}

variable "retencao_logs_dias" {
  description = "Retencao dos log groups no CloudWatch."
  type        = number
  default     = 30
}

# ---------------------------------------------------------------------------------------------------------
# Dimensionamento (docs/capacidade-ecs-fargate.md). Todos os numeros sao estimativas a validar com o k6.
# ---------------------------------------------------------------------------------------------------------

variable "visao_consolidada" {
  description = <<-EOT
    Servico da rota visao consolidada (DynamoDB + Valkey), CPU-bound.
    cpu/memory                        = combinacao valida do Fargate (unidades de CPU e MiB)
    min_tasks/max_tasks               = limites do autoscaling
    alvo_requisicoes_por_task_minuto  = alvo do ALBRequestCountPerTarget (metrica POR MINUTO: RPS-alvo por task x 60)
    alvo_cpu                          = alvo de uma segunda politica por CPU media (null desliga)
    scale_out_cooldown/scale_in_cooldown = segundos
    java_tool_options                 = JAVA_TOOL_OPTIONS extra (vazio = ergonomia da JVM)
  EOT
  type = object({
    cpu                              = optional(number, 2048)
    memory                           = optional(number, 4096)
    min_tasks                        = optional(number, 12)
    max_tasks                        = optional(number, 150)
    alvo_requisicoes_por_task_minuto = optional(number, 33000)
    alvo_cpu                         = optional(number, 60)
    scale_out_cooldown               = optional(number, 60)
    scale_in_cooldown                = optional(number, 300)
    java_tool_options                = optional(string, "")
  })
  default = {}

  validation {
    condition     = var.visao_consolidada.min_tasks >= 1 && var.visao_consolidada.min_tasks <= var.visao_consolidada.max_tasks
    error_message = "visao_consolidada: min_tasks deve ser >= 1 e <= max_tasks."
  }
}

variable "fonte_primaria" {
  description = <<-EOT
    Servico da rota fonte primaria (gateway mainframe), I/O-bound e limitado pela cota.
    max_tasks tambem vira CONSULTAS_FONTE_PRIMARIA_COTA_MAX_TASKS: cada task limita-se a cota_chamadas_por_segundo / max_tasks
    (ADR-0015), entao aumentar max_tasks sem aumentar a cota NAO aumenta a vazao.
    alvo_cpu = null por padrao (a vazao e limitada pela cota, nao pela CPU).
  EOT
  type = object({
    cpu                              = optional(number, 1024)
    memory                           = optional(number, 2048)
    min_tasks                        = optional(number, 2)
    max_tasks                        = optional(number, 3)
    cota_chamadas_por_segundo        = optional(number, 1500)
    alvo_requisicoes_por_task_minuto = optional(number, 1500)
    alvo_cpu                         = optional(number)
    scale_out_cooldown               = optional(number, 60)
    scale_in_cooldown                = optional(number, 600)
    java_tool_options                = optional(string, "")
  })
  default = {}

  validation {
    condition     = var.fonte_primaria.min_tasks >= 1 && var.fonte_primaria.min_tasks <= var.fonte_primaria.max_tasks
    error_message = "fonte_primaria: min_tasks deve ser >= 1 e <= max_tasks."
  }

  validation {
    condition     = var.fonte_primaria.cota_chamadas_por_segundo >= var.fonte_primaria.max_tasks
    error_message = "fonte_primaria: a cota precisa dar ao menos 1 chamada/s por task (cota >= max_tasks)."
  }
}

variable "tags" {
  description = "Tags aplicadas a todos os recursos."
  type        = map(string)
  default = {
    app        = "consultas-operacoes-credito"
    managed-by = "terraform"
  }
}
