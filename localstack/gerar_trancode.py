"""Gera os stubs de trancode do gateway do mainframe (WireMock) a partir da massa da visão consolidada.

Uso: python localstack/gerar_trancode.py   (também chamado ao fim de localstack/gerar_massa.py)

Requer PyYAML. Cada localstack/massa/<id_operacao>/<BLOCO>.json é codificado com o layout do bloco em
src/main/resources/trancode/layouts/<bloco>.yml, o mesmo que a API usa para ler o trancode. Assim a fonte
primária e a visão consolidada devolvem o mesmo JSON para a mesma operação.

Saída: wiremock/mappings/<id_operacao>.json, um stub por bloco existente:
    GET /mainframe/operacoes/<id_operacao>/blocos/<bloco>  ->  200 text/plain com o trancode
Bloco ausente na massa não tem stub (o WireMock responde 404: bloco indisponível); operação inexistente
também responde 404.

Regras de codificação (inversas das do parser da API):
- registro: campos de posição fixa (inicio a partir de 1, relativo ao registro), seguidos das listas
  aninhadas na ordem do layout;
- lista: prefixo de 3 dígitos com a quantidade de itens, seguido dos registros;
- numero: zeros à esquerda; decimal: casas implícitas (formato = casas), zeros à esquerda;
- data/data_hora: no formato do layout; booleano: formato "SN" (verdadeiro, falso);
- texto: alinhado à esquerda com espaços; null: o campo inteiro em espaços.
"""
import datetime as dt
import json
import pathlib
import re
import shutil
from decimal import Decimal

import yaml

RAIZ = pathlib.Path(__file__).resolve().parent.parent
MASSA = RAIZ / "localstack" / "massa"
LAYOUTS = RAIZ / "src" / "main" / "resources" / "trancode" / "layouts"
MAPPINGS = RAIZ / "wiremock" / "mappings"
TAMANHO_QUANTIDADE = 3

# Tokens de DateTimeFormatter usados nos layouts -> strftime
TOKENS_DATA = [("yyyy", "%Y"), ("MM", "%m"), ("dd", "%d"), ("HH", "%H"), ("mm", "%M"), ("ss", "%S"), ("'T'", "T")]


def padrao_strftime(formato):
    for java, python in TOKENS_DATA:
        formato = formato.replace(java, python)
    return formato


def codificar_campo(campo, valor):
    tamanho = campo["tamanho"]
    if valor is None:
        return " " * tamanho
    tipo = campo["tipo"]
    if tipo == "numero":
        texto = str(int(valor)).rjust(tamanho, "0")
    elif tipo == "decimal":
        casas = int(campo["formato"])
        texto = str(int(Decimal(valor).scaleb(casas))).rjust(tamanho, "0")
    elif tipo in ("data", "data_hora"):
        formato = padrao_strftime(campo["formato"])
        leitura = dt.date.fromisoformat if tipo == "data" else dt.datetime.fromisoformat
        texto = leitura(valor).strftime(formato)
    elif tipo == "booleano":
        texto = campo.get("formato", "SN")[0 if valor else 1]
    elif tipo == "texto":
        texto = str(valor).ljust(tamanho)
    else:
        raise ValueError(f"tipo desconhecido: {tipo}")
    if len(texto) != tamanho:
        raise ValueError(f"{campo['campo']}: valor {valor!r} não cabe em {tamanho} posições")
    return texto


def codificar_registro(registro, conteudo):
    campos = registro["campos"]
    tamanho = max(c["inicio"] + c["tamanho"] - 1 for c in campos)
    texto = [" "] * tamanho
    for campo in campos:
        inicio = campo["inicio"] - 1
        texto[inicio:inicio + campo["tamanho"]] = codificar_campo(campo, conteudo.get(campo["campo"]))
    saida = "".join(texto)
    for lista in registro.get("listas", []):
        saida += codificar_lista(lista["registro"], conteudo.get(lista["campo"]) or [])
    return saida


def codificar_lista(registro, itens):
    return str(len(itens)).rjust(TAMANHO_QUANTIDADE, "0") + "".join(
        codificar_registro(registro, item) for item in itens)


def codificar_bloco(layout, conteudo):
    if layout["estrutura"] == "lista":
        return codificar_lista(layout["registro"], conteudo)
    return codificar_registro(layout["registro"], conteudo)


def stub(id_operacao, bloco, trancode):
    return {
        "request": {"method": "GET", "url": f"/mainframe/operacoes/{id_operacao}/blocos/{bloco}"},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "text/plain; charset=utf-8"},
            "body": trancode,
        },
    }


def carregar_layouts():
    return {arquivo.stem: yaml.safe_load(arquivo.read_text(encoding="utf-8"))
            for arquivo in sorted(LAYOUTS.glob("*.yml"))}


def main():
    layouts = carregar_layouts()
    if MAPPINGS.exists():
        shutil.rmtree(MAPPINGS)
    MAPPINGS.mkdir(parents=True)
    for diretorio in sorted(p for p in MASSA.iterdir() if p.is_dir() and re.fullmatch(r"\d+", p.name)):
        stubs = []
        for arquivo in sorted(diretorio.glob("*.json")):
            bloco = arquivo.stem.lower()
            conteudo = json.loads(arquivo.read_text(encoding="utf-8"), parse_float=Decimal)
            stubs.append(stub(diretorio.name, bloco, codificar_bloco(layouts[bloco], conteudo)))
        MAPPINGS.joinpath(f"{diretorio.name}.json").write_text(
            json.dumps({"mappings": stubs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"{diretorio.name}: {len(stubs)} stubs de trancode")


if __name__ == "__main__":
    main()
