package pl.piomin.services.adapters.in.web;

import pl.piomin.services.domain.CriterioConsulta;

/** Header e query params da consulta, convertidos em critério de consulta. */
record ParametrosConsulta(String tipoConsulta, String idOperacao, String cpfCnpj, String conta,
                          String limiteOperacoes) {

    static final String HEADER_TIPO_CONSULTA = "x-tipo-consulta";
    static final String ID_OPERACAO = "id_operacao";
    static final String CPF_CNPJ = "cpf_cnpj";
    static final String CONTA = "conta";
    static final String LIMITE_OPERACOES = "limite_operacoes";

    CriterioConsulta criterio() {
        return new CriterioConsulta.PorOperacao(Long.parseLong(idOperacao));
    }
}
