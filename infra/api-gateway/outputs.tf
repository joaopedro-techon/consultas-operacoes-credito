output "invoke_url" {
  description = "URL base do stage. As rotas ficam em <invoke_url>/v1/operacoes-credito e <invoke_url>/v1/operacoes-credito/fonte-primaria."
  value       = aws_api_gateway_stage.this.invoke_url
}

output "rest_api_id" {
  description = "Id da REST API."
  value       = aws_api_gateway_rest_api.this.id
}

output "api_key_ids" {
  description = "Id da API key por consumidor. E este id que o backend recebe no header x-consumidor."
  value       = { for name, key in aws_api_gateway_api_key.consumer : name => key.id }
}

output "api_key_values" {
  description = "Valor da API key por consumidor (enviar no header x-api-key). Ler com: terraform output -json api_key_values"
  value       = { for name, key in aws_api_gateway_api_key.consumer : name => key.value }
  sensitive   = true
}

output "usage_plan_ids" {
  description = "Id do Usage Plan por consumidor."
  value       = { for name, plan in aws_api_gateway_usage_plan.consumer : name => plan.id }
}
