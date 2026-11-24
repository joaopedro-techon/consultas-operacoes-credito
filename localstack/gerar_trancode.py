"""Gera os stubs de trancode do gateway do mainframe (WireMock) a partir da massa da visão consolidada.

Uso: python localstack/gerar_trancode.py   (também chamado ao fim de localstack/gerar_massa.py)

Requer PyYAML. Cada localstack/massa/<id_operacao>/<CUSTODIA>/<BLOCO>.json é codificado com o layout do bloco
naquela custódia, o mesmo que a API usa para ler o trancode:
src/main/resources/trancode/layouts/<CUSTODIA>/<bloco>.yml, se existir, senão o layout base
src/main/resources/trancode/layouts/<bloco>.yml. Assim a fonte primária e a visão consolidada devolvem o
mesmo JSON para a mesma operação.

Saída:
- wiremock/mappings/<id_operacao>.json, um stub por custódia × bloco existente × página:
    GET /mainframe/custodias/<CUSTODIA>/operacoes/<id_operacao>/blocos/<bloco>[?chave_continuacao=<chave>]
      ->  200 text/plain com o trancode
  Bloco ausente na massa, ou operação que não está naquela custódia, não tem stub (o WireMock responde 404).
- wiremock/mappings/clientes.json e contas.json, um stub por custódia × cliente (ou conta) × página, a partir
  do indices.env de cada operação:
    GET /mainframe/custodias/<CUSTODIA>/clientes/<cpf_cnpj>/operacoes[?chave_continuacao=<chave>]
    GET /mainframe/custodias/<CUSTODIA>/contas/<conta>/operacoes[?chave_continuacao=<chave>]
  A lista usa o layout lista_operacoes.yml (id_operacao + data_contratacao_operacao) e traz só as operações
  daquela custódia, sem ordem garantida (aqui, por id_operacao crescente; a API ordena). Cliente ou conta sem
  operações na custódia não tem stub (404: nenhuma operação nela).
  A primeira página casa só sem o query param chave_continuacao (queryParameters absent); as seguintes, com a
  chave exata devolvida na página anterior.

Regras de codificação (inversas das do parser da API):
- registro: campos de posição fixa (inicio a partir de 1, relativo ao registro), seguidos das listas
  aninhadas na ordem do layout;
- lista de nível superior (layout com estrutura: lista): paginada, no máximo 30 ocorrências por página.
  Cada página = quantidade nesta página (3 dígitos) + indicador de continuação (1: S há mais páginas, N última)
  + chave de continuação (20, alinhada à esquerda com espaços; em branco quando N) + as ocorrências. Lista
  vazia = "000N" + 20 espaços. A chave é <NOME DO BLOCO, até 14>+<posição da próxima ocorrência, 6 dígitos>
  (ex.: PARCELAS000031);
- lista aninhada num registro (ex.: saldo.saldo_parcelas): prefixo de 3 dígitos com a quantidade, sem paginação;
- campo com ignorar: true (existe no trancode da custódia, fora do contrato): valor fixo plausível pelo tipo;
- numero: zeros à esquerda; decimal: casas implícitas (formato = casas), zeros à esquerda. Sem sinal: valor
  negativo, ou decimal com mais casas que o formato, interrompe a geração (nada é truncado em silêncio);
- data/data_hora: no formato do layout; booleano: formato "SN" (verdadeiro, falso);
- texto: alinhado à esquerda com espaços; null ou ausente: o campo inteiro em espaços.
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
CUSTODIAS = ["SF", "F5", "OD", "NSF"]
TAMANHO_QUANTIDADE = 3
OCORRENCIAS_POR_PAGINA = 30
TAMANHO_CHAVE = 20
PARAMETRO_CHAVE = "chave_continuacao"

# Tokens de DateTimeFormatter usados nos layouts -> strftime
TOKENS_DATA = [("yyyy", "%Y"), ("MM", "%m"), ("dd", "%d"), ("HH", "%H"), ("mm", "%M"), ("ss", "%S"), ("'T'", "T")]

# Valor gravado nos campos ignorar: true, por tipo
VALOR_IGNORADO = {
    "texto": "CNSG01", "numero": 1, "decimal": Decimal("0"), "booleano": True,
    "data": "2026-01-01", "data_hora": "2026-01-01T00:00:00",
}


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
    tipo = campo["tipo"]
    if campo.get("ignorar"):
        valor = VALOR_IGNORADO[tipo]
        if tipo == "texto":
            valor = valor[:tamanho]
    if valor is None:
        return " " * tamanho
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
    conhecidos = {c["campo"] for c in campos} | {lista["campo"] for lista in registro.get("listas", [])}
    sobrando = set(conteudo) - conhecidos
    if sobrando:
        raise ValueError(f"campos fora do layout: {sorted(sobrando)}")
    tamanho = max(c["inicio"] + c["tamanho"] - 1 for c in campos)
    texto = [" "] * tamanho
    for campo in campos:
        inicio = campo["inicio"] - 1
        texto[inicio:inicio + campo["tamanho"]] = codificar_campo(campo, conteudo.get(campo["campo"]))
    saida = "".join(texto)
    for lista in registro.get("listas", []):
        saida += codificar_sublista(lista["registro"], conteudo.get(lista["campo"]) or [])
    return saida


def codificar_sublista(registro, itens):
    """Lista aninhada num registro: prefixo de 3 dígitos com a quantidade, sem paginação."""
    return str(len(itens)).rjust(TAMANHO_QUANTIDADE, "0") + "".join(
        codificar_registro(registro, item) for item in itens)


def chave_continuacao(nome, proxima):
    """Chave opaca e estável da página que começa na ocorrência 'proxima' (1-based)."""
    chave = f"{nome.upper()[:TAMANHO_CHAVE - 6]}{proxima:06d}"
    assert len(chave) <= TAMANHO_CHAVE
    return chave


def paginas(layout, conteudo):
    """[(chave pedida ou None na primeira página, trancode da página)]."""
    if layout["estrutura"] != "lista":
        return [(None, codificar_registro(layout["registro"], conteudo))]
    resultado = []
    pedida = None
    inicio = 0
    while True:
        pagina = conteudo[inicio:inicio + OCORRENCIAS_POR_PAGINA]
        fim = inicio + len(pagina)
        continua = fim < len(conteudo)
        proxima = chave_continuacao(layout["bloco"], fim + 1) if continua else ""
        trancode = (str(len(pagina)).rjust(TAMANHO_QUANTIDADE, "0") + ("S" if continua else "N")
                    + proxima.ljust(TAMANHO_CHAVE)
                    + "".join(codificar_registro(layout["registro"], item) for item in pagina))
        resultado.append((pedida, trancode))
        if not continua:
            return resultado
        pedida, inicio = proxima, fim


LAYOUT_LISTA_OPERACOES = "lista_operacoes"
# Atributo do indices.env -> rota da lista no gateway e arquivo de mappings
LISTAS = {"CPF_CNPJ_CLIENTE": ("clientes", "clientes"), "CONTA_CLIENTE": ("contas", "contas")}


def stub(url_path, chave, trancode):
    parametro = {"absent": True} if chave is None else {"equalTo": chave}
    return {
        "request": {"method": "GET", "urlPath": url_path, "queryParameters": {PARAMETRO_CHAVE: parametro}},
        "response": {
            "status": 200,
            "headers": {"Content-Type": "text/plain; charset=utf-8"},
            "body": trancode,
        },
    }


def stubs_paginados(url_path, layout, conteudo):
    return [stub(url_path, chave, trancode) for chave, trancode in paginas(layout, conteudo)]


def carregar_layouts():
    """{(custodia ou None para a base, bloco): layout}."""
    layouts = {}
    for arquivo in sorted(LAYOUTS.glob("*.yml")):
        layouts[(None, arquivo.stem)] = ler_layout(arquivo)
    for custodia in CUSTODIAS:
        for arquivo in sorted(LAYOUTS.joinpath(custodia).glob("*.yml")):
            if (None, arquivo.stem) not in layouts:
                raise ValueError(f"{arquivo}: layout de custódia sem layout base")
            layouts[(custodia, arquivo.stem)] = ler_layout(arquivo)
    return layouts


def ler_layout(arquivo):
    layout = yaml.safe_load(arquivo.read_text(encoding="utf-8"))
    if layout["bloco"] != arquivo.stem:
        raise ValueError(f"{arquivo}: bloco {layout['bloco']} diferente do nome do arquivo")
    return layout


def layout_de(layouts, custodia, bloco):
    return layouts.get((custodia, bloco)) or layouts[(None, bloco)]


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


def stubs_das_listas(layouts, operacoes):
    """Stubs das listas por custódia × cliente/conta; operacoes: (custodia, id, data_contratacao, indices)."""
    for atributo, (rota, arquivo) in LISTAS.items():
        por_chave = {}
        for custodia, id_operacao, data_contratacao, indices in operacoes:
            if indices.get(atributo):
                por_chave.setdefault((CUSTODIAS.index(custodia), indices[atributo]), []).append(
                    {"id_operacao": id_operacao, "data_contratacao_operacao": data_contratacao})
        stubs = []
        for (indice_custodia, chave), itens in sorted(por_chave.items()):
            custodia = CUSTODIAS[indice_custodia]
            itens.sort(key=lambda item: item["id_operacao"])
            stubs += stubs_paginados(f"/mainframe/custodias/{custodia}/{rota}/{chave}/operacoes",
                                     layout_de(layouts, custodia, LAYOUT_LISTA_OPERACOES), itens)
        gravar(arquivo, stubs)
        print(f"{arquivo}: {len(por_chave)} listas de operações ({len(stubs)} páginas)")


def main():
    layouts = carregar_layouts()
    if MAPPINGS.exists():
        shutil.rmtree(MAPPINGS)
    MAPPINGS.mkdir(parents=True)
    operacoes = []
    for diretorio in sorted(p for p in MASSA.iterdir() if p.is_dir() and re.fullmatch(r"\d+", p.name)):
        stubs = []
        indices = ler_indices(diretorio)
        for custodia in (c for c in CUSTODIAS if diretorio.joinpath(c).is_dir()):
            for arquivo in sorted(diretorio.joinpath(custodia).glob("*.json")):
                bloco = arquivo.stem.lower()
                conteudo = json.loads(arquivo.read_text(encoding="utf-8"), parse_float=Decimal)
                url_path = f"/mainframe/custodias/{custodia}/operacoes/{diretorio.name}/blocos/{bloco}"
                stubs += stubs_paginados(url_path, layout_de(layouts, custodia, bloco), conteudo)
                if bloco == "operacao":
                    operacoes.append((custodia, conteudo["id_operacao"], conteudo["data_contratacao_operacao"],
                                      indices))
        gravar(diretorio.name, stubs)
        print(f"{diretorio.name}: {len(stubs)} stubs de trancode")
    stubs_das_listas(layouts, operacoes)


if __name__ == "__main__":
    main()
