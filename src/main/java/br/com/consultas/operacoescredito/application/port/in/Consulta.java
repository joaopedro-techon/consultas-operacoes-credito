package br.com.consultas.operacoescredito.application.port.in;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pedido de consulta: como localizar as operações, quais blocos devolver e quais campos de cada bloco.
 * {@code operacao} e {@code originacao} são sempre incluídos.
 *
 * @param campos itens do {@code fields} ({@code bloco.campo}), ainda não validados; vazio devolve blocos completos
 */
public record Consulta(CriterioConsulta criterio, Set<Bloco> blocos, List<String> campos) {

    public Consulta {
        Objects.requireNonNull(criterio, "criterio");
        Objects.requireNonNull(blocos, "blocos");
        var todos = Bloco.semprePresentes();
        todos.addAll(blocos);
        blocos = Collections.unmodifiableSet(EnumSet.copyOf(todos));
        campos = campos == null ? List.of() : List.copyOf(campos);
    }

    /** Consulta com os blocos pedidos, todos completos. */
    public Consulta(CriterioConsulta criterio, Set<Bloco> blocos) {
        this(criterio, blocos, List.of());
    }

    /** Consulta só com os blocos que vêm sempre. */
    public static Consulta comBlocosPadrao(CriterioConsulta criterio) {
        return new Consulta(criterio, Set.of());
    }
}
