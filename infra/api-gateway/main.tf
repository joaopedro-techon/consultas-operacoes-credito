locals {
  # Rotas expostas. Ambas usam o mesmo contrato (headers, query params, corpo);
  # so muda a fonte de dados no backend.
  routes = {
    consolidada = {
      resource_id = aws_api_gateway_resource.operacoes_credito.id
      path        = "/v1/operacoes-credito"
    }
    fonte_primaria = {
      resource_id = aws_api_gateway_resource.fonte_primaria.id
      path        = "/v1/operacoes-credito/fonte-primaria"
    }
  }

  # Corpo Problem Details (RFC 9457) para erros devolvidos pelo proprio gateway.
  gateway_errors = {
    THROTTLED = {
      status = "429"
      title  = "Too Many Requests"
      detail = "Limite de requisicoes do Usage Plan excedido. Tente novamente mais tarde."
    }
    QUOTA_EXCEEDED = {
      status = "429"
      title  = "Too Many Requests"
      detail = "Cota do Usage Plan esgotada para o periodo."
    }
    INVALID_API_KEY = {
      status = "403"
      title  = "Forbidden"
      detail = "API key ausente ou invalida. Envie o header x-api-key."
    }
  }
}

resource "aws_api_gateway_rest_api" "this" {
  name        = var.api_name
  description = "API de Consulta de Operacoes de Credito (v1)."

  # A key vem sempre do header x-api-key.
  api_key_source = "HEADER"

  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

# /v1
resource "aws_api_gateway_resource" "v1" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_rest_api.this.root_resource_id
  path_part   = "v1"
}

# /v1/operacoes-credito
resource "aws_api_gateway_resource" "operacoes_credito" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.v1.id
  path_part   = "operacoes-credito"
}

# /v1/operacoes-credito/fonte-primaria
resource "aws_api_gateway_resource" "fonte_primaria" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.operacoes_credito.id
  path_part   = "fonte-primaria"
}

resource "aws_api_gateway_method" "get" {
  for_each = local.routes

  rest_api_id      = aws_api_gateway_rest_api.this.id
  resource_id      = each.value.resource_id
  http_method      = "GET"
  authorization    = "NONE"
  api_key_required = true

  # Declarado (opcional) para poder ecoa-lo nas respostas de erro do gateway.
  # Validacao do formato e da aplicacao (400 Problem Details).
  request_parameters = {
    "method.request.header.x-correlationid" = false
  }
}

# HTTP_PROXY repassa metodo, headers (incluindo x-correlationid e x-tipo-consulta)
# e query string sem alteracao. A unica sobrescrita e o x-consumidor, preenchido
# com o id da API key usada; um x-consumidor enviado pelo cliente e substituido.
resource "aws_api_gateway_integration" "get" {
  for_each = local.routes

  rest_api_id             = aws_api_gateway_rest_api.this.id
  resource_id             = each.value.resource_id
  http_method             = aws_api_gateway_method.get[each.key].http_method
  type                    = "HTTP_PROXY"
  integration_http_method = "GET"
  uri                     = "${var.backend_base_url}${each.value.path}"
  timeout_milliseconds    = var.integration_timeout_ms

  request_parameters = {
    "integration.request.header.x-consumidor" = "context.identity.apiKeyId"
  }
}

resource "aws_api_gateway_gateway_response" "problem" {
  for_each = local.gateway_errors

  rest_api_id   = aws_api_gateway_rest_api.this.id
  response_type = each.key
  status_code   = each.value.status

  response_parameters = {
    "gatewayresponse.header.x-correlationid" = "method.request.header.x-correlationid"
    "gatewayresponse.header.Content-Type"    = "'application/problem+json'"
  }

  response_templates = {
    "application/json" = jsonencode({
      type       = "about:blank"
      title      = each.value.title
      status     = tonumber(each.value.status)
      detail     = each.value.detail
      request_id = "$context.requestId"
    })
  }
}

resource "aws_api_gateway_deployment" "this" {
  rest_api_id = aws_api_gateway_rest_api.this.id

  # Redeploy sempre que rotas, integracoes ou respostas do gateway mudarem.
  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_resource.v1,
      aws_api_gateway_resource.operacoes_credito,
      aws_api_gateway_resource.fonte_primaria,
      aws_api_gateway_method.get,
      aws_api_gateway_integration.get,
      aws_api_gateway_gateway_response.problem,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "this" {
  rest_api_id   = aws_api_gateway_rest_api.this.id
  deployment_id = aws_api_gateway_deployment.this.id
  stage_name    = var.stage_name
}

# Uma API key por consumidor. O valor e gerado pela AWS e so sai como output sensivel.
resource "aws_api_gateway_api_key" "consumer" {
  for_each = var.consumers

  name        = "${var.api_name}-${var.stage_name}-${each.key}"
  description = "Consumidor ${each.key} da ${var.api_name} (${var.stage_name})."
  enabled     = true
}

# Um Usage Plan por consumidor, para que rate/burst/quota sejam individuais.
resource "aws_api_gateway_usage_plan" "consumer" {
  for_each = var.consumers

  name        = "${var.api_name}-${var.stage_name}-${each.key}"
  description = "Usage Plan do consumidor ${each.key}."

  api_stages {
    api_id = aws_api_gateway_rest_api.this.id
    stage  = aws_api_gateway_stage.this.stage_name
  }

  throttle_settings {
    rate_limit  = each.value.rate_limit
    burst_limit = each.value.burst_limit
  }

  dynamic "quota_settings" {
    for_each = each.value.quota == null ? [] : [each.value.quota]

    content {
      limit  = quota_settings.value.limit
      period = quota_settings.value.period
      offset = quota_settings.value.offset
    }
  }
}

resource "aws_api_gateway_usage_plan_key" "consumer" {
  for_each = var.consumers

  key_id        = aws_api_gateway_api_key.consumer[each.key].id
  key_type      = "API_KEY"
  usage_plan_id = aws_api_gateway_usage_plan.consumer[each.key].id
}
