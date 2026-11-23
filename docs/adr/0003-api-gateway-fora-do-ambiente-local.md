# API Gateway e Usage Plans fora do ambiente local; LocalStack Community fixado

Desde março/2026 o LocalStack exige auth token e o plano gratuito é só para uso não comercial; a última imagem Community aberta valida API key mas não aplica throttling nem devolve 429. Por isso o Usage Plan (rate limit, 429, injeção do consumidor em header) é definido como IaC e validado na AWS real, não no docker-compose.

Localmente usamos a última tag Community do LocalStack, fixada, para DynamoDB (com GSIs) e, em breve, SQS — preferida ao DynamoDB Local por cobrir os dois serviços num só container.

## Consequences

A imagem fixada não recebe patches de segurança nem novas paridades com a AWS; serve só para desenvolvimento local, nunca para ambientes compartilhados.
