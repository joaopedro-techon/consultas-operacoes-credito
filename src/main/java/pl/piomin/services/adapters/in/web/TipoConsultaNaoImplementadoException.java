package pl.piomin.services.adapters.in.web;

import pl.piomin.services.domain.TipoConsulta;

/** Tipo de consulta válido, mas ainda não disponível nesta versão da API (501). */
class TipoConsultaNaoImplementadoException extends RuntimeException {

    TipoConsultaNaoImplementadoException(TipoConsulta tipo) {
        super("Consulta do tipo " + tipo + " ainda não está disponível");
    }
}
