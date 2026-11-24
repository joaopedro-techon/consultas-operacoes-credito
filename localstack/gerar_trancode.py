"""Gera os stubs de trancode do gateway do mainframe (WireMock) a partir da massa da visão consolidada.

Uso: python localstack/gerar_trancode.py   (também chamado ao fim de localstack/gerar_massa.py)

Requer PyYAML. Cada localstack/massa/<id_operacao>/<BLOCO>.json é codificado com o layout do bloco em
src/main/resources/trancode/layouts/<bloco>.yml, o mesmo que a API usa para ler o trancode. Assim a fonte
primária e a visão consolidada devolvem o mesmo JSON para a mesma operação.

Saída:
- wiremock/mappings/<id_operacao>.json, um stub por bloco existente:
    GET /mainframe/operacoes/<id_operacao>/blocos/<bloco>  ->  200 text/plain com o trancode
  Bloco ausente na massa não tem stub (o WireMock responde 404: bloco indisponível); operação inexistente
  também responde 404.
- wiremock/mappings/clientes.json e contas.json, um stub por cliente e por conta da massa (indices.env):
    GET /mainframe/clientes/<cpf_cnpj>/operacoes  ->  200 text/plain com a lista de operações
    GET /mainframe/contas/<conta>/operacoes       ->  idem
  A lista usa o layout lista_operacoes.yml (id_operacao + data_contratacao_operacao), da mais recente para a
  mais antiga por data de contratação e, no empate, por id_operacao (mesma ordem do sk_gsi da visão
  consolidada). Cliente ou conta sem operações não tem stub (404: a API devolve lista vazia).

Regras de codificação (inversas das do parser da API):
- registro: campos de posição fixa (inicio a partir de 1, relativo ao registro), seguidos das listas
  aninhadas na ordem do layout;
- lista: prefixo de 3 dígitos com a quantidade de itens, seguido dos registros;
- numero: zeros à esquerda; decimal: casas implícitas (formato = casas), zeros à esquerda. Sem sinal: valor
  negativo, ou decimal com mais casas que o formato, interrompe a geração (nada é truncado em silêncio);
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


def digitos_sem_sinal(campo, numero, valor):
    """Os layouts não têm posição de sinal: número negativo não tem codificação (e viraria 000-123)."""
    if numero < 0:
        raise ValueError(f"{campo['campo']}: valor {valor!r} negativo; o layout não define sinal")
    return str(numero)


def codificar_campo(campo, valor):
    tamanho = campo["tamanho"]
    if valor is None:
        return " " * tamanho
    tipo = campo["tipo"]
    if tipo == "numero":
        if Decimal(valor) != int(valor):
            raise ValueError(f"{campo['campo']}: valor {valor!r} não é inteiro")
        texto = digitos_sem_sinal(campo, int(valor), valor).rjust(tamanho, "0")
    elif tipo == "decimal":
        casas = int(campo["formato"])
        escalado = Decimal(valor).scaleb(casas)
        if escalado != escalado.to_integral_value():
            raise ValueError(f"{campo['campo']}: valor {valor!r} tem mais de {casas} casas decimais")
        texto = digitos_sem_sinal(campo, int(escalado), valor).rjust(tamanho, "0")
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


LAYOUT_LISTA_OPERACOES = "lista_operacoes"
# Atributo do indices.env -> rota da lista no gateway
LISTAS = {"CPF_CNPJ_CLIENTE": ("clientes", "clientes"), "CONTA_CLIENTE": ("contas", "contas")}


def stub(id_operacao, bloco, trancode):
    return stub_url(f"/mainframe/operacoes/{id_operacao}/blocos/{bloco}", trancode)


def stub_url(url, trancode):
    return {
        "request": {"method": "GET", "url": url},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "text/plain; charset=utf-8"},
            "body": trancode,
        },
    }


def carregar_layouts():
    return {arquivo.stem: yaml.safe_load(arquivo.read_text(encoding="utf-8"))
            for arquivo in sorted(LAYOUTS.glob("*.yml"))}


def ler_indices(diretorio):
    indices = {}
    for linha in diretorio.joinpath("indices.env").read_text(encoding="utf-8").splitlines():
        if "=" in linha:
            chave, valor = linha.split("=", 1)
            indices[chave.strip()] = valor.strip()
    return indices


def gravar(nome, stubs):
    MAPPINGS.joinpath(f"{nome}.json").write_text(
        json.dumps({"mappings": stubs}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def stubs_das_listas(layout, operacoes):
    """Um stub de lista por cliente e por conta; operacoes: (id_operacao, data_contratacao, indices)."""
    for atributo, (rota, arquivo) in LISTAS.items():
        por_chave = {}
        for id_operacao, data_contratacao, indices in operacoes:
            if indices.get(atributo):
                por_chave.setdefault(indices[atributo], []).append(
                    {"id_operacao": id_operacao, "data_contratacao_operacao": data_contratacao})
        stubs = []
        for chave, itens in sorted(por_chave.items()):
            itens.sort(key=lambda item: (item["data_contratacao_operacao"], item["id_operacao"]), reverse=True)
            stubs.append(stub_url(f"/mainframe/{rota}/{chave}/operacoes", codificar_bloco(layout, itens)))
        gravar(arquivo, stubs)
        print(f"{arquivo}: {len(stubs)} listas de operações")


def main():
    layouts = carregar_layouts()
    if MAPPINGS.exists():
        shutil.rmtree(MAPPINGS)
    MAPPINGS.mkdir(parents=True)
    operacoes = []
    for diretorio in sorted(p for p in MASSA.iterdir() if p.is_dir() and re.fullmatch(r"\d+", p.name)):
        stubs = []
        for arquivo in sorted(diretorio.glob("*.json")):
            bloco = arquivo.stem.lower()
            conteudo = json.loads(arquivo.read_text(encoding="utf-8"), parse_float=Decimal)
            stubs.append(stub(diretorio.name, bloco, codificar_bloco(layouts[bloco], conteudo)))
            if bloco == "operacao":
                operacoes.append((conteudo["id_operacao"], conteudo["data_contratacao_operacao"],
                                  ler_indices(diretorio)))
        gravar(diretorio.name, stubs)
        print(f"{diretorio.name}: {len(stubs)} stubs de trancode")
    stubs_das_listas(layouts[LAYOUT_LISTA_OPERACOES], operacoes)


if __name__ == "__main__":
    main()
