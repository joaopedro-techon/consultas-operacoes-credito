package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import java.util.List;

/**
 * Layout do trancode de um bloco (ou da lista de operações), lido de {@code trancode/layouts/<bloco>.yml} ou do
 * override da custódia em {@code trancode/layouts/<CUSTODIA>/<bloco>.yml}.
 * <ul>
 *     <li>Bloco-objeto: um registro;</li>
 *     <li>Bloco-lista (e lista de operações): uma página de ocorrências por chamada (ADR-0016), com cabeçalho de
 *     quantidade ({@value #TAMANHO_QUANTIDADE} dígitos, no máximo {@value #MAXIMO_POR_PAGINA}), indicador de
 *     continuação ({@code S}/{@code N}) e chave de continuação ({@value #TAMANHO_CHAVE_CONTINUACAO} posições,
 *     em branco na última página), seguido dos registros;</li>
 *     <li>Sub-listas dentro de um registro: prefixo de quantidade e registros, sem paginação.</li>
 * </ul>
 *
 * @param bloco     nome canônico do bloco (o mesmo do arquivo)
 * @param estrutura objeto ou lista
 * @param registro  campos de posição fixa e sub-listas de cada registro
 */
record LayoutTrancode(String bloco, Estrutura estrutura, Registro registro) {

    static final int TAMANHO_QUANTIDADE = 3;
    static final int TAMANHO_CHAVE_CONTINUACAO = 20;
    static final int MAXIMO_POR_PAGINA = 30;

    enum Estrutura { OBJETO, LISTA }

    /** {@code true} quando cada chamada devolve uma página de ocorrências (bloco-lista ou lista de operações). */
    boolean paginado() {
        return estrutura == Estrutura.LISTA;
    }

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
     * @param ignorar {@code true} para um campo que a custódia manda mas não faz parte do contrato: ocupa as
     *                posições, mas não é convertido nem devolvido
     */
    record Campo(String campo, int inicio, int tamanho, TipoCampoTrancode tipo, String formato, Boolean ignorar) {

        /** {@code ignorar} ausente no YAML: o campo é lido normalmente. */
        Campo {
            ignorar = Boolean.TRUE.equals(ignorar);
        }

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
