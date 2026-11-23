package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.filtro.ExpressaoFiltro;
import br.com.consultas.operacoescredito.domain.filtro.OperadorComparacao;
import cz.jirutka.rsql.parser.RSQLParser;
import cz.jirutka.rsql.parser.RSQLParserException;
import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.ComparisonOperator;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.NoArgRSQLVisitorAdapter;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLOperators;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Traduz o parâmetro {@code filter} (RSQL) para a {@link ExpressaoFiltro} do domínio; a biblioteca fica só aqui. */
final class FiltroRsql {

    private static final RSQLParser PARSER = new RSQLParser();

    private static final Map<ComparisonOperator, OperadorComparacao> OPERADORES = Map.of(
            RSQLOperators.EQUAL, OperadorComparacao.IGUAL,
            RSQLOperators.NOT_EQUAL, OperadorComparacao.DIFERENTE,
            RSQLOperators.LESS_THAN, OperadorComparacao.MENOR,
            RSQLOperators.LESS_THAN_OR_EQUAL, OperadorComparacao.MENOR_OU_IGUAL,
            RSQLOperators.GREATER_THAN, OperadorComparacao.MAIOR,
            RSQLOperators.GREATER_THAN_OR_EQUAL, OperadorComparacao.MAIOR_OU_IGUAL,
            RSQLOperators.IN, OperadorComparacao.EM,
            RSQLOperators.NOT_IN, OperadorComparacao.FORA);

    private FiltroRsql() {
    }

    /** Ausente ou vazio: sem filtro. */
    static Optional<ExpressaoFiltro> traduzir(String filter) {
        if (filter == null || filter.isBlank()) {
            return Optional.empty();
        }
        Node raiz;
        try {
            raiz = PARSER.parse(filter);
        } catch (RSQLParserException e) {
            throw new RequisicaoInvalidaException("Parâmetro " + ParametrosConsulta.FILTER
                    + " com sintaxe RSQL inválida. Exemplo: parcelas.numero_parcela_amortizacao=ge=4;"
                    + "parcelas.situacao_parcela==ABERTA. Operadores: ==, !=, =lt=, =le=, =gt=, =ge=, =in=, =out=; "
                    + "';' é 'e' e ',' é 'ou'");
        }
        return Optional.of(raiz.accept(new Tradutor()));
    }

    private static final class Tradutor extends NoArgRSQLVisitorAdapter<ExpressaoFiltro> {

        @Override
        public ExpressaoFiltro visit(AndNode node) {
            return new ExpressaoFiltro.E(filhos(node.getChildren()));
        }

        @Override
        public ExpressaoFiltro visit(OrNode node) {
            return new ExpressaoFiltro.Ou(filhos(node.getChildren()));
        }

        @Override
        public ExpressaoFiltro visit(ComparisonNode node) {
            return new ExpressaoFiltro.Comparacao(node.getSelector(), OPERADORES.get(node.getOperator()),
                    node.getArguments());
        }

        private List<ExpressaoFiltro> filhos(List<Node> filhos) {
            return filhos.stream().map(filho -> filho.accept(this)).toList();
        }
    }
}
