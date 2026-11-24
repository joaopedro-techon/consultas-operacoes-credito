package br.com.consultas.operacoescredito.domain;

import java.util.Objects;
import java.util.OptionalInt;

/** Como as operações de crédito são localizadas: uma variante por tipo de consulta. */
public sealed interface CriterioConsulta {

    /** Maior valor aceito em {@code limite_operacoes}. */
    int LIMITE_OPERACOES_MAXIMO = 500;

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

    /**
     * Operações do cliente, das mais recentes para as mais antigas por data de contratação.
     *
     * @param cpfCnpj          CPF ou CNPJ (numérico ou alfanumérico) com DV válido
     * @param limiteOperacoes  quantas operações devolver; vazio devolve todas
     */
    record PorCliente(String cpfCnpj, OptionalInt limiteOperacoes) implements CriterioConsulta {

        public PorCliente {
            if (!CpfCnpj.valido(cpfCnpj)) {
                throw new IllegalArgumentException("cpf_cnpj inválido");
            }
            validarLimite(limiteOperacoes);
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.CLIENTE;
        }
    }

    /**
     * Operações vinculadas à conta, das mais recentes para as mais antigas por data de contratação.
     *
     * @param conta            número da conta, só dígitos
     * @param limiteOperacoes  quantas operações devolver; vazio devolve todas
     */
    record PorConta(String conta, OptionalInt limiteOperacoes) implements CriterioConsulta {

        public PorConta {
            if (conta == null || conta.isEmpty() || !conta.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("conta deve ter só dígitos");
            }
            validarLimite(limiteOperacoes);
        }

        @Override
        public TipoConsulta tipo() {
            return TipoConsulta.CONTA;
        }
    }

    private static void validarLimite(OptionalInt limiteOperacoes) {
        Objects.requireNonNull(limiteOperacoes, "limiteOperacoes");
        limiteOperacoes.ifPresent(limite -> {
            if (limite < 1 || limite > LIMITE_OPERACOES_MAXIMO) {
                throw new IllegalArgumentException("limite_operacoes deve estar entre 1 e " + LIMITE_OPERACOES_MAXIMO);
            }
        });
    }
}
