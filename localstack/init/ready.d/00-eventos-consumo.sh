#!/bin/bash
# Cria a fila SQS dos eventos de consumo (ADR-0004). Roda antes da carga da visão consolidada
# (ordem alfabética em /etc/localstack/init/ready.d), para que a fila já exista quando o
# LocalStack sinalizar VISAO_CONSOLIDADA_PRONTA (docker-compose e Testcontainers).
set -euo pipefail

FILA="${CONSULTAS_EVENTOS_FILA:-consultas-eventos-consumo}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "Criando fila ${FILA}"
awslocal sqs create-queue --queue-name "${FILA}" > /dev/null
echo "EVENTOS_CONSUMO_PRONTA"
