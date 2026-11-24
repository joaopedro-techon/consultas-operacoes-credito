package br.com.consultas.operacoescredito.domain.filtro;

import java.util.List;
import java.util.Objects;

/**
 * Árvore do parâmetro {@code filter}, independente da sintaxe (RSQL) em que chegou. Os seletores são
 * caminhos com ponto começando pelo bloco ({@code parcelas.situacao_parcela}); a validação contra o
 * catálogo de campos e a tipagem dos argumentos acontecem em {@link FiltroDeOperacoes#compilar}.
 */
public sealed interface ExpressaoFiltro {

    /** Todas as condições ({@code ;} no RSQL). */
    record E(List<ExpressaoFiltro> operandos) implements ExpressaoFiltro {
        public E {
            operandos = List.copyOf(operandos);
        }
    }

    /** Alguma das condições ({@code ,} no RSQL). */
    record Ou(List<ExpressaoFiltro> operandos) implements ExpressaoFiltro {
        public Ou {
            operandos = List.copyOf(operandos);
        }
    }

    /** Comparação de um campo com um ou mais argumentos, ainda como texto. */
    record Comparacao(String seletor, OperadorComparacao operador, List<String> argumentos)
            implements ExpressaoFiltro {
        public Comparacao {
            Objects.requireNonNull(seletor, "seletor");
            Objects.requireNonNull(operador, "operador");
            argumentos = List.copyOf(argumentos);
        }
    }
}
