package br.com.consultas.operacoescredito.application.port.out;

import br.com.consultas.operacoescredito.domain.TipoConsulta;

/** A fonte de operações ainda não atende este tipo de consulta. */
public class TipoConsultaNaoSuportadoException extends RuntimeException {

    private final TipoConsulta tipo;

    public TipoConsultaNaoSuportadoException(TipoConsulta tipo, String fonte) {
        super("Consulta " + tipo + " ainda não disponível na " + fonte);
        this.tipo = tipo;
    }

    public TipoConsulta tipo() {
        return tipo;
    }
}
