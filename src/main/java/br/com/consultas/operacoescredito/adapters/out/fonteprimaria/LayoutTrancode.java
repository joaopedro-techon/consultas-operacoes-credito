package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import java.util.List;

/**
 * Layout do trancode de um bloco, lido de {@code trancode/layouts/<bloco>.yml}.
 * Bloco-objeto: um registro. Bloco-lista: prefixo de 3 dígitos com a quantidade + registros.
 *
 * @param bloco     nome canônico do bloco (o mesmo do arquivo)
 * @param estrutura objeto ou lista
 * @param registro  campos de posição fixa e sub-listas de cada registro
 */
record LayoutTrancode(String bloco, Estrutura estrutura, Registro registro) {

    static final int TAMANHO_QUANTIDADE = 3;

    enum Estrutura { OBJETO, LISTA }

    /**
     * Registro: campos de posição fixa ({@code inicio} relativo ao registro, a partir de 1), seguidos
     * das sub-listas na ordem declarada, cada uma com o mesmo esquema de prefixo de quantidade.
     */
    record Registro(List<Campo> campos, List<Lista> listas) {

        Registro {
            campos = campos == null ? List.of() : List.copyOf(campos);
            listas = listas == null ? List.of() : List.copyOf(listas);
        }

        /** Tamanho da parte fixa: até a última posição ocupada por um campo. */
        int tamanho() {
            return campos.stream().mapToInt(Campo::fim).max().orElse(0);
        }
    }

    /** Sub-lista de um registro (ex.: {@code saldo_parcelas} no bloco {@code saldo}). */
    record Lista(String campo, Registro registro) {
    }

    /**
     * Campo de posição fixa.
     *
     * @param formato data/data_hora: padrão do {@code DateTimeFormatter}; decimal: casas implícitas;
     *                booleano: caracteres de verdadeiro e falso (default {@code SN}); demais: não usado
     */
    record Campo(String campo, int inicio, int tamanho, TipoCampoTrancode tipo, String formato) {

        /** Última posição (inclusiva) do campo no registro. */
        int fim() {
            return inicio + tamanho - 1;
        }

        Object converter(String registro) {
            var valor = registro.substring(inicio - 1, fim());
            if (valor.isBlank()) {
                return null;
            }
            try {
                return tipo.converter(valor, formato);
            } catch (RuntimeException e) {
                throw new TrancodeInvalidoException(
                        "Campo " + campo + " com valor inválido para " + tipo.nome() + ": '" + valor + "'", e);
            }
        }
    }
}
