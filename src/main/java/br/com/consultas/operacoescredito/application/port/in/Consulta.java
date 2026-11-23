package br.com.consultas.operacoescredito.application.port.in;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.filtro.ExpressaoFiltro;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pedido de consulta: como localizar as operações, quais blocos devolver e, opcionalmente, o filtro
 * sobre o conteúdo deles. {@code operacao} e {@code originacao} são sempre incluídos.
 */
public record Consulta(CriterioConsulta criterio, Set<Bloco> blocos, Optional<ExpressaoFiltro> filtro) {

    public Consulta {
        Objects.requireNonNull(criterio, "criterio");
        Objects.requireNonNull(blocos, "blocos");
        Objects.requireNonNull(filtro, "filtro");
        var todos = Bloco.semprePresentes();
        todos.addAll(blocos);
        blocos = Collections.unmodifiableSet(EnumSet.copyOf(todos));
    }

    /** Consulta sem filtro. */
    public Consulta(CriterioConsulta criterio, Set<Bloco> blocos) {
        this(criterio, blocos, Optional.empty());
    }

    /** Consulta só com os blocos que vêm sempre. */
    public static Consulta comBlocosPadrao(CriterioConsulta criterio) {
        return new Consulta(criterio, Set.of());
    }
}
