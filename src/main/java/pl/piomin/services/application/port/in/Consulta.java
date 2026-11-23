package pl.piomin.services.application.port.in;

import pl.piomin.services.domain.Bloco;
import pl.piomin.services.domain.CriterioConsulta;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pedido de consulta: como localizar as operações e quais blocos devolver.
 * {@code operacao} e {@code originacao} são sempre incluídos.
 */
public record Consulta(CriterioConsulta criterio, Set<Bloco> blocos) {

    public Consulta {
        Objects.requireNonNull(criterio, "criterio");
        Objects.requireNonNull(blocos, "blocos");
        var todos = Bloco.semprePresentes();
        todos.addAll(blocos);
        blocos = Collections.unmodifiableSet(EnumSet.copyOf(todos));
    }

    /** Consulta só com os blocos que vêm sempre. */
    public static Consulta comBlocosPadrao(CriterioConsulta criterio) {
        return new Consulta(criterio, Set.of());
    }
}
