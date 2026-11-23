#!/bin/bash
# Cria a tabela da visão consolidada (ADR-0001) e carrega a massa de dados.
# Executado pelo LocalStack em /etc/localstack/init/ready.d (docker-compose e Testcontainers).
set -euo pipefail

export TABELA="${CONSULTAS_DYNAMODB_TABELA:-operacoes-credito}"
export MASSA_DIR="${MASSA_DIR:-/etc/localstack/massa}"
# awslocal e boto3 precisam usar a mesma região
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

gsi() {
  local nome="$1" pk="$2"
  printf '{"IndexName":"%s","KeySchema":[{"AttributeName":"%s","KeyType":"HASH"},{"AttributeName":"sk_gsi","KeyType":"RANGE"}],"Projection":{"ProjectionType":"ALL"}}' "$nome" "$pk"
}

echo "Criando tabela ${TABELA}"
awslocal dynamodb create-table \
  --table-name "${TABELA}" \
  --billing-mode PAY_PER_REQUEST \
  --attribute-definitions \
    AttributeName=numero_operacao,AttributeType=N \
    AttributeName=custodia_expand,AttributeType=S \
    AttributeName=cpf_cnpj_cliente,AttributeType=S \
    AttributeName=conta_cliente,AttributeType=S \
    AttributeName=sk_gsi,AttributeType=S \
  --key-schema \
    AttributeName=numero_operacao,KeyType=HASH \
    AttributeName=custodia_expand,KeyType=RANGE \
  --global-secondary-indexes "[$(gsi gsi-cliente cpf_cnpj_cliente),$(gsi gsi-conta conta_cliente)]" \
  > /dev/null

# A carga usa boto3 (já presente na imagem) porque o AWS CLI v1 da imagem não decodifica Base64
# em atributos binários: o texto_detalhe precisa ser gravado como os bytes GZIP.
#
# Cada operação é um diretório massa/<numero_operacao> com:
#   indices.env  -> CPF_CNPJ_CLIENTE e CONTA_CLIENTE (atributos dos GSIs esparsos, só no item OPERACAO)
#   <BLOCO>.json -> payload do bloco
python3 - <<'PYTHON'
import gzip, json, os, pathlib, datetime
import boto3

CODIGO_EXPAND = {"OPERACAO": 1, "ORIGINACAO": 2, "COMPONENTES": 3, "PARCELAS": 4, "AMORTIZACOES": 5,
                 "RECEBIMENTOS": 6, "PLANO": 7, "SALDO": 8, "CONTROLES_IOF": 9}

dynamodb = boto3.client("dynamodb", endpoint_url="http://localhost:4566",
                        region_name=os.environ["AWS_DEFAULT_REGION"],
                        aws_access_key_id="test", aws_secret_access_key="test")
tabela = os.environ["TABELA"]
agora = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")

for diretorio in sorted(p for p in pathlib.Path(os.environ["MASSA_DIR"]).iterdir() if p.is_dir()):
    numero_operacao = diretorio.name
    indices = dict(linha.split("=", 1) for linha in diretorio.joinpath("indices.env").read_text().split())
    for arquivo in sorted(diretorio.glob("*.json")):
        bloco = arquivo.stem
        payload = json.loads(arquivo.read_text(encoding="utf-8"))
        minificado = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        item = {
            "numero_operacao": {"N": numero_operacao},
            "custodia_expand": {"S": f"SF#EXPAND#{bloco}"},
            "codigo_expand": {"N": str(CODIGO_EXPAND[bloco])},
            "texto_detalhe": {"B": gzip.compress(minificado, mtime=0)},
            "data_insercao": {"S": agora},
            "data_alteracao": {"S": agora},
        }
        if bloco == "OPERACAO":
            item["cpf_cnpj_cliente"] = {"S": indices["CPF_CNPJ_CLIENTE"]}
            item["conta_cliente"] = {"S": indices["CONTA_CLIENTE"]}
            item["sk_gsi"] = {"S": f"{payload['data_contratacao_operacao']}#{numero_operacao}"}
        dynamodb.put_item(TableName=tabela, Item=item)
    print(f"Operacao {numero_operacao} carregada", flush=True)
PYTHON

echo "VISAO_CONSOLIDADA_PRONTA"
