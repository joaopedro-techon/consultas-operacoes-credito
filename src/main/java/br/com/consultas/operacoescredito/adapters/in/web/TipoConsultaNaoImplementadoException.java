package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.TipoConsulta;

/** Tipo de consulta válido, mas ainda não disponível nesta versão da API (501). */
class TipoConsultaNaoImplementadoException extends RuntimeException {

    TipoConsultaNaoImplementadoException(TipoConsulta tipo) {
        super("Consulta do tipo " + tipo + " ainda não está disponível");
    }
}
