variable "aws_region" {
  description = "Regiao AWS onde a REST API sera criada."
  type        = string
}

variable "api_name" {
  description = "Nome da REST API no API Gateway."
  type        = string
  default     = "consultas-operacoes-credito"
}

variable "stage_name" {
  description = "Nome do stage do API Gateway (ex.: dev, hml, prd). O prefixo /v1 faz parte do path dos recursos, nao do stage."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[A-Za-z0-9_-]+$", var.stage_name))
    error_message = "stage_name aceita apenas letras, digitos, '-' e '_'."
  }
}

variable "backend_base_url" {
  description = "URL base do backend Spring Boot, sem barra final (ex.: https://consultas.interno.exemplo). As rotas /v1/... sao anexadas a ela."
  type        = string

  validation {
    condition     = can(regex("^https?://[^/]+(/.*[^/])?$", var.backend_base_url))
    error_message = "backend_base_url deve comecar com http:// ou https:// e nao terminar com '/'."
  }
}

variable "integration_timeout_ms" {
  description = "Timeout da integracao HTTP em milissegundos (50 a 29000). Deve ser maior que o maior prazo da aplicacao (400 ms) para que o 504 venha do backend com Problem Details."
  type        = number
  default     = 1000

  validation {
    condition     = var.integration_timeout_ms >= 50 && var.integration_timeout_ms <= 29000
    error_message = "integration_timeout_ms deve estar entre 50 e 29000."
  }
}

variable "consumers" {
  description = <<-EOT
    Consumidores da API. A chave do mapa e o nome do consumidor; cada um recebe uma API key e um Usage Plan proprio.
    rate_limit  = requisicoes por segundo (steady state)
    burst_limit = rajada maxima
    quota       = opcional; limite de requisicoes por periodo (DAY, WEEK ou MONTH)
  EOT
  type = map(object({
    rate_limit  = number
    burst_limit = number
    quota = optional(object({
      limit  = number
      period = string
      offset = optional(number, 0)
    }))
  }))

  validation {
    condition     = length(var.consumers) > 0
    error_message = "Informe ao menos um consumidor."
  }

  validation {
    condition     = alltrue([for c in values(var.consumers) : c.rate_limit > 0 && c.burst_limit > 0])
    error_message = "rate_limit e burst_limit devem ser maiores que zero."
  }

  validation {
    condition     = alltrue([for c in values(var.consumers) : c.quota == null ? true : contains(["DAY", "WEEK", "MONTH"], c.quota.period)])
    error_message = "quota.period deve ser DAY, WEEK ou MONTH."
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
