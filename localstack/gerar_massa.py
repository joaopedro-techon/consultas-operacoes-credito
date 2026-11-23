"""Gera a massa da visão consolidada em localstack/massa/<id_operacao>/.

Uso: python localstack/gerar_massa.py

A massa é derivada de uma especificação curta (OPERACOES, abaixo) para que os blocos fechem entre si:

- parcelas: principal e juros constantes por parcela; vencimento mensal a partir da contratação;
- recebimentos: cada um é aplicado às parcelas em aberto, da mais antiga para a mais nova, juros antes
  do principal; cada aplicação vira uma amortização com a mesma data_valor_movimento;
- parcela LIQUIDADA quando principal + juros foram totalmente amortizados;
- saldo: saldo_devedor de cada parcela = principal + juros - amortizado; saldo_devedor_operacao = soma;
  quantidade_dias_atraso = dias desde o vencimento da parcela em aberto mais antiga já vencida;
- componentes: LIBERACAO = principal total; IOF = soma do IOF devido dos controles; IOF_FLAT = 0,38%
  do principal; SEGURO informado na especificação;
- controles_iof: IOF devido por parcela = 0,0082% ao dia sobre o principal da parcela, até 365 dias;
  um controle por amortização de principal (pago na data dela) e, se a parcela não está liquidada,
  um controle final com o IOF ainda devido e não pago;
- operação LIQUIDADA quando todas as parcelas estão liquidadas, com data_baixa = último recebimento.

Ao final, gera os stubs de trancode da fonte primária com localstack/gerar_trancode.py (requer PyYAML).

Para incluir uma operação, acrescente uma entrada em OPERACOES e rode o script. Várias operações podem
compartilhar cpf_cnpj e conta (consultas CLIENTE e CONTA). Blocos listados em "sem_blocos" não são
gerados (bloco indisponível na visão consolidada).
"""
import datetime as dt
import json
import pathlib
import re
import shutil
from decimal import ROUND_HALF_EVEN, Decimal

import gerar_trancode

DATA_POSICAO = dt.date(2026, 11, 23)
CENTAVO = Decimal("0.01")
TAXA_IOF_DIARIA = Decimal("0.000082")
TAXA_IOF_FLAT = Decimal("0.0038")

OPERACOES = [
    {
        "id": 100000001, "cpf_cnpj": "52998224725", "conta": "434115000065874",
        "contratacao": "2026-05-21", "canal": "MOBILE", "dependencia": "004",
        "parcelas": 12, "principal": "6000.00", "taxa": "1.80", "seguro": "120.00",
        "recebimentos": [
            ("2026-06-21T06:00:00", "608.00", "DESCONTO_FOLHA"),
            ("2026-07-21T06:00:00", "608.00", "DESCONTO_FOLHA"),
            ("2026-08-21T06:00:00", "608.00", "DESCONTO_FOLHA"),
            ("2026-09-21T06:00:00", "608.00", "DESCONTO_FOLHA"),
            ("2026-10-21T06:00:00", "608.00", "DESCONTO_FOLHA"),
            ("2026-11-10T14:32:10", "300.00", "ANTECIPACAO_CLIENTE"),
        ],
    },
    {
        "id": 100000002, "cpf_cnpj": "52998224725", "conta": "434115000065874",
        "contratacao": "2025-09-15", "canal": "AGENCIA", "dependencia": "012",
        "parcelas": 6, "principal": "1800.00", "taxa": "1.50", "seguro": "0.00",
        "recebimentos": [
            ("2025-10-15T06:00:00", "327.00", "DESCONTO_FOLHA"),
            ("2025-11-15T06:00:00", "327.00", "DESCONTO_FOLHA"),
            ("2025-12-15T06:00:00", "327.00", "DESCONTO_FOLHA"),
            ("2026-01-20T10:15:00", "981.00", "ANTECIPACAO_CLIENTE"),
        ],
    },
    {
        "id": 100000003, "cpf_cnpj": "52998224725", "conta": "434115000099991",
        "contratacao": "2026-10-28", "canal": "INTERNET", "dependencia": "004",
        "parcelas": 24, "principal": "12000.00", "taxa": "1.65", "seguro": "240.00",
        "recebimentos": [],
        "sem_blocos": ["PLANO", "SALDO"],
    },
    {
        "id": 100000004, "cpf_cnpj": "12ABC34501DE35", "conta": "434115000077712",
        "contratacao": "2026-08-05", "canal": "AGENCIA", "dependencia": "031",
        "parcelas": 10, "principal": "50000.00", "taxa": "1.20", "seguro": "0.00",
        "recebimentos": [
            ("2026-09-05T08:00:00", "5600.00", "DEBITO_CONTA"),
            ("2026-10-05T08:00:00", "5600.00", "DEBITO_CONTA"),
            ("2026-11-05T08:00:00", "5600.00", "DEBITO_CONTA"),
        ],
    },
    {
        "id": 100000005, "cpf_cnpj": "12ABC34501DE35", "conta": "434115000077712",
        "contratacao": "2026-02-10", "canal": "INTERNET", "dependencia": "031",
        "parcelas": 4, "principal": "20000.00", "taxa": "1.10", "seguro": "0.00",
        "recebimentos": [
            ("2026-03-10T08:00:00", "5220.00", "DEBITO_CONTA"),
            ("2026-04-10T08:00:00", "5220.00", "DEBITO_CONTA"),
            ("2026-05-10T08:00:00", "5220.00", "DEBITO_CONTA"),
            ("2026-06-10T08:00:00", "5220.00", "DEBITO_CONTA"),
        ],
    },
    {
        "id": 100000006, "cpf_cnpj": "11144477735", "conta": "434115000088823",
        "contratacao": "2026-09-12", "canal": "MOBILE", "dependencia": "004",
        "parcelas": 8, "principal": "2400.00", "taxa": "2.10", "seguro": "48.00",
        "recebimentos": [
            ("2026-10-12T06:00:00", "350.40", "DESCONTO_FOLHA"),
            ("2026-11-12T06:00:00", "350.40", "DESCONTO_FOLHA"),
        ],
    },
]


def dec(valor):
    """Marca um Decimal para sair como número JSON com a escala original (ex.: 500.00)."""
    return f"__DEC__{valor.quantize(CENTAVO)}"


def somar_meses(data, meses):
    mes = data.month - 1 + meses
    return data.replace(year=data.year + mes // 12, month=mes % 12 + 1)


def gerar(spec):
    op = spec["id"]
    contratacao = dt.date.fromisoformat(spec["contratacao"])
    n = spec["parcelas"]
    principal_total = Decimal(spec["principal"])
    principal = (principal_total / n).quantize(CENTAVO)
    juros = (principal_total * Decimal(spec["taxa"]) / 100).quantize(CENTAVO)
    assert principal * n == principal_total, f"{op}: principal não divide igualmente"

    parcelas = []
    for numero in range(1, n + 1):
        vencimento = somar_meses(contratacao, numero)
        dias_iof = min((vencimento - contratacao).days, 365)
        parcelas.append({
            "numero": numero, "vencimento": vencimento,
            "principal": principal, "juros": juros,
            "pago_principal": Decimal(0), "pago_juros": Decimal(0),
            "iof": (principal * TAXA_IOF_DIARIA * dias_iof).quantize(CENTAVO, ROUND_HALF_EVEN),
            "iof_controles": [], "liquidada_em": None,
        })

    amortizacoes, recebimentos = [], []
    for data_hora, valor, motivo in spec["recebimentos"]:
        restante = Decimal(valor)
        liquidadas = 0
        parcial = False
        for parcela in parcelas:
            if restante == 0:
                break
            devido_juros = parcela["juros"] - parcela["pago_juros"]
            devido_principal = parcela["principal"] - parcela["pago_principal"]
            if devido_juros + devido_principal == 0:
                continue
            aplicado_juros = min(restante, devido_juros)
            restante -= aplicado_juros
            aplicado_principal = min(restante, devido_principal)
            restante -= aplicado_principal
            parcela["pago_juros"] += aplicado_juros
            parcela["pago_principal"] += aplicado_principal
            amortizacoes.append({
                "id_operacao": op,
                "numero_parcela_amortizacao": parcela["numero"],
                "numero_plano_amortizacao": 1,
                "valor_principal_amortizado": dec(aplicado_principal),
                "valor_juros_principal_amortizado": dec(aplicado_juros),
                "indicador_ocorrencia_valida": True,
                "data_valor_movimento": data_hora,
            })
            if aplicado_principal > 0:
                parcela["iof_controles"].append((aplicado_principal, data_hora[:10]))
            if parcela["pago_juros"] == parcela["juros"] and parcela["pago_principal"] == parcela["principal"]:
                parcela["liquidada_em"] = data_hora[:10]
                liquidadas += 1
            else:
                parcial = True
        assert restante == 0, f"{op}: recebimento de {valor} em {data_hora} maior que o saldo"
        tipo = ("BAIXA_PARCIAL_PARCELA" if parcial
                else "BAIXA_INTEGRAL_MAIS_UMA_PARCELA" if liquidadas > 1
                else "BAIXA_INTEGRAL_PARCELA")
        recebimentos.append({
            "id_operacao": op, "data_valor_movimento": data_hora, "valor_recebido": dec(Decimal(valor)),
            "tipo_baixa": tipo, "motivo_baixa": motivo,
        })

    controles_iof = []
    for parcela in parcelas:
        sequencial = 0
        iof_restante = parcela["iof"]
        for valor_principal, data in parcela["iof_controles"]:
            sequencial += 1
            quitou = parcela["liquidada_em"] == data and sequencial == len(parcela["iof_controles"])
            devido = iof_restante if quitou else (
                parcela["iof"] * valor_principal / parcela["principal"]).quantize(CENTAVO)
            iof_restante -= devido
            controles_iof.append(controle_iof(op, parcela["numero"], sequencial, devido, devido, data))
        if parcela["liquidada_em"] is None:
            controles_iof.append(controle_iof(op, parcela["numero"], sequencial + 1, iof_restante,
                                              Decimal(0), None))

    liquidada = all(p["liquidada_em"] for p in parcelas)
    vencidas_em_aberto = [p for p in parcelas if p["liquidada_em"] is None and p["vencimento"] < DATA_POSICAO]
    saldo_parcelas = [{
        "id_operacao": op, "numero_parcela_amortizacao": p["numero"], "numero_plano_amortizacao": 1,
        "saldo_devedor": dec(p["principal"] + p["juros"] - p["pago_principal"] - p["pago_juros"]),
    } for p in parcelas]
    iof_total = sum((p["iof"] for p in parcelas), Decimal(0))

    blocos = {
        "OPERACAO": {
            "id_operacao": op,
            "situacao_operacao_credito": "LIQUIDADA" if liquidada else "ATIVA",
            "data_contratacao_operacao": spec["contratacao"],
            "data_baixa_operacao": spec["recebimentos"][-1][0][:10] if liquidada else None,
            "cpf_cnpj": spec["cpf_cnpj"],
        },
        "ORIGINACAO": {
            "id_operacao": op, "canal_contratacao": spec["canal"], "dependencia_contabil": spec["dependencia"],
        },
        "COMPONENTES": [
            componente(op, 1, "LIBERACAO", principal_total),
            componente(op, 2, "IOF", iof_total),
            componente(op, 3, "IOF_FLAT", (principal_total * TAXA_IOF_FLAT).quantize(CENTAVO)),
            componente(op, 4, "SEGURO", Decimal(spec["seguro"])),
        ],
        "PARCELAS": [{
            "id_operacao": op, "numero_parcela_amortizacao": p["numero"], "numero_plano_amortizacao": 1,
            "data_vencimento_parcela": p["vencimento"].isoformat(),
            "valor_principal_parcela": dec(p["principal"]), "valor_juros_principal_parcela": dec(p["juros"]),
            "situacao_parcela": "LIQUIDADA" if p["liquidada_em"] else "ABERTA",
        } for p in parcelas],
        "AMORTIZACOES": amortizacoes,
        "RECEBIMENTOS": recebimentos,
        "PLANO": {
            "id_operacao": op,
            "numero_plano_amortizacao_ativo": 1,
            "dados_plano_amortizacao": [{
                "taxa_juros_nominal": dec(Decimal(spec["taxa"])),
                "taxa_juros_efetiva": dec(((1 + Decimal(spec["taxa"]) / 100) ** 12 - 1) * 100),
                "situacao_plano_amortizacao": "ATIVO",
                "numero_plano_amortizacao_ativo": 1,
                "encargos_moratorios": [
                    {"dia_inicio": 0, "dia_fim": 30, "taxa_mora": dec(Decimal("1.00")),
                     "taxa_multa": dec(Decimal("2.00"))},
                    {"dia_inicio": 31, "dia_fim": 9999, "taxa_mora": dec(Decimal("1.00")),
                     "taxa_multa": dec(Decimal("2.00"))},
                ],
                "desconto_bom_pagador": [
                    {"quantidade_dias_atraso": 0, "percentual_desconto": dec(Decimal("5.00"))},
                ],
            }],
        },
        "SALDO": {
            "id_operacao": op,
            "saldo_devedor_operacao": dec(sum((p["principal"] + p["juros"] - p["pago_principal"] - p["pago_juros"]
                                               for p in parcelas), Decimal(0))),
            "quantidade_dias_atraso": (DATA_POSICAO - vencidas_em_aberto[0]["vencimento"]).days
            if vencidas_em_aberto else 0,
            "data_posicao_calculo": DATA_POSICAO.isoformat(),
            "saldo_parcelas": saldo_parcelas,
        },
        "CONTROLES_IOF": controles_iof,
    }
    for ausente in spec.get("sem_blocos", []):
        del blocos[ausente]
    return blocos


def componente(op, numero, tipo, valor):
    codigos = {"LIBERACAO": 1, "IOF": 2, "IOF_FLAT": 3, "SEGURO": 4}
    return {
        "id_operacao": op, "numero_componente_financeiro": numero,
        "codigo_tipo_componente_financeiro": codigos[tipo], "tipo_componente_financeiro": tipo,
        "valor_componente_financeiro": dec(valor), "situacao_componente_financeiro": "ATIVO",
    }


def controle_iof(op, parcela, sequencial, devido, pago, data):
    return {
        "id_operacao": op, "numero_parcela_amortizacao": parcela, "numero_plano_amortizacao": 1,
        "numero_sequencial_controle_iof": sequencial,
        "valor_iof_devido": dec(devido), "valor_iof_pago": dec(pago), "data_pagamento_iof": data,
    }


def para_json(conteudo):
    texto = json.dumps(conteudo, ensure_ascii=False, indent=2)
    return re.sub(r'"__DEC__(-?\d+\.\d+)"', r"\1", texto) + "\n"


def main():
    massa = pathlib.Path(__file__).parent / "massa"
    if massa.exists():
        shutil.rmtree(massa)
    for spec in OPERACOES:
        diretorio = massa / str(spec["id"])
        diretorio.mkdir(parents=True)
        diretorio.joinpath("indices.env").write_text(
            f"CPF_CNPJ_CLIENTE={spec['cpf_cnpj']}\nCONTA_CLIENTE={spec['conta']}\n", encoding="utf-8", newline="\n")
        for bloco, conteudo in gerar(spec).items():
            diretorio.joinpath(f"{bloco}.json").write_text(para_json(conteudo), encoding="utf-8", newline="\n")
        print(f"{spec['id']} gerada")
    # Stubs de trancode da fonte primária derivados da mesma massa (mantém as duas fontes coerentes)
    gerar_trancode.main()


if __name__ == "__main__":
    main()
