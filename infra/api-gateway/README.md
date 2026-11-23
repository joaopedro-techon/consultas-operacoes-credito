# API Gateway (REST API v1) — IaC

Terraform da porta de entrada da API de Consulta de Operações de Crédito. Não sobe no docker-compose (ADR-0003): Usage Plan, 429 e injeção de `x-consumidor` só são validados numa conta AWS real.

## O que é criado

| Recurso | Detalhe |
| --- | --- |
| REST API (regional) | `GET /v1/operacoes-credito` e `GET /v1/operacoes-credito/fonte-primaria`, ambas com `api_key_required = true` (header `x-api-key`) |
| Integração `HTTP_PROXY` | `<backend_base_url>/v1/...`; método, headers (`x-tipo-consulta`, `x-correlationid`) e query string repassados sem alteração |
| Header `x-consumidor` | Preenchido com `context.identity.apiKeyId`; sobrescreve qualquer valor enviado pelo cliente |
| API keys | Uma por entrada de `var.consumers`, valor gerado pela AWS |
| Usage Plans | Um por consumidor, com `rate_limit`/`burst_limit` e `quota` opcional |
| Gateway responses | `THROTTLED` e `QUOTA_EXCEEDED` (429), `INVALID_API_KEY` (403) em Problem Details, ecoando `x-correlationid` |
| Stage | `var.stage_name` (o `/v1` está no path, não no stage) |

## Pré-requisitos

- Terraform >= 1.6, provider `hashicorp/aws` `~> 6.73`.
- Credenciais AWS da conta alvo no ambiente (`AWS_PROFILE` ou variáveis `AWS_*`). Nada de credencial em arquivo versionado.
- Backend publicado e acessível pelo API Gateway na `backend_base_url`.

## Aplicar

```powershell
cd infra/api-gateway
Copy-Item terraform.tfvars.example terraform.tfvars   # git-ignored; ajuste região, URL e consumidores
terraform init                                         # ou: terraform init -backend-config=backend.hcl (estado remoto em S3)
terraform plan -out tfplan
terraform apply tfplan
terraform output invoke_url
terraform output api_key_ids
terraform output -json api_key_values                  # sensível: não colar em tickets ou logs
```

Mudanças em rotas, integrações ou gateway responses geram um novo deployment automaticamente (`triggers` em `aws_api_gateway_deployment`).

## Destruir

```powershell
cd infra/api-gateway
terraform destroy
```

Remove API, stage, keys e Usage Plans. Os consumidores perdem o acesso imediatamente.

## Validação manual na AWS (critérios do ticket #5)

Com `$URL = terraform output -raw invoke_url` e `$KEY` = valor de uma key de `api_key_values`:

1. **Sem key → 403**
   `curl.exe -i "$URL/v1/operacoes-credito?id_operacao=1" -H "x-tipo-consulta: OPERACAO"` → `403`, corpo Problem Details.
   Repetir com uma key inválida (`-H "x-api-key: invalida"`) → `403`.
2. **Com key → backend responde**
   `curl.exe -i "$URL/v1/operacoes-credito?id_operacao=1" -H "x-tipo-consulta: OPERACAO" -H "x-api-key: $KEY" -H "x-correlationid: teste-123"` → status da aplicação (200/404), header `x-correlationid: teste-123` de volta.
   Repetir em `/v1/operacoes-credito/fonte-primaria`.
3. **Backend recebe `x-consumidor`**
   No log/span do backend para a chamada do passo 2, `x-consumidor` = id da key (`terraform output api_key_ids`), não `desconhecido`.
   Enviar `-H "x-consumidor: forjado"` e confirmar que o backend continua recebendo o id da key.
   Confirmar também que a query string chegou intacta (`expand`, `fields`, `filter` com caracteres como `=ge=` e `;`).
4. **Excesso → 429**
   Usar um consumidor com limites baixos (ex.: `rate_limit = 1`, `burst_limit = 1`) e disparar rajada:
   `1..30 | ForEach-Object { curl.exe -s -o NUL -w "%{http_code} " "$URL/v1/operacoes-credito?id_operacao=1" -H "x-tipo-consulta: OPERACAO" -H "x-api-key: $KEY" }`
   Esperado: parte das respostas `429` com corpo Problem Details e `x-correlationid`. Confirmar e registrar o status exato observado.
   Se houver `quota`, esgotá-la e confirmar `429` de `QUOTA_EXCEEDED`.

## Pontos em aberto

- **Key ausente vs. inválida:** com `api_key_required = true`, chamada sem key é barrada antes da integração, então o backend nunca recebe `x-consumidor` vazio. Confirmar na AWS que a ausência de key cai no response type `INVALID_API_KEY` (e não em `MISSING_AUTHENTICATION_TOKEN`, que é o de path inexistente).
- **`x-consumidor` é o id da key, não o nome do consumidor.** O mapeamento id → nome está em `terraform output api_key_ids`; se o backend precisar do nome, a alternativa é um authorizer, fora do escopo da v1.
- **Backend exposto diretamente** permitiria forjar `x-consumidor`. Em ambiente real o backend deve aceitar tráfego só do API Gateway (VPC Link/NLB privado, security group ou certificado de cliente). Este módulo não cria essa camada.
- **Timeout da integração** padrão 1000 ms, acima do maior prazo da aplicação (400 ms), para que o 504 seja o Problem Details do backend e não o do gateway.
- **Access logs/métricas do stage** não estão habilitados: exigem a role de CloudWatch configurada na conta (`aws_api_gateway_account`), recurso regional compartilhado que não deve ser gerenciado por esta stack.
- **`.terraform.lock.hcl`** ainda não versionado: gerar no primeiro `terraform init` e commitar.
