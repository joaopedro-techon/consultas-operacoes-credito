package br.com.consultas.operacoescredito.domain;

/**
 * Como as operações de crédito são localizadas. Cada tipo de consulta terá a sua variante;
 * por enquanto só existe a consulta por operação.
 */
public sealed interface CriterioConsulta {

    TipoConsulta tipo();

    record PorOperacao(long idOperacao) implements CriterioConsulta {

        public PorOperacao {
            if (idOperacao <= 0) {
                throw new IllegalArgumentException("id_operacao deve ser positivo");
            }
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.OPERACAO;
        }
    }
}
