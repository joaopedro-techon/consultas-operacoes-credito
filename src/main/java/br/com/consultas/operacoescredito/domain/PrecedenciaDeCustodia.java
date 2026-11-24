package br.com.consultas.operacoescredito.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordem que decide a custódia de uma operação encontrada em mais de uma ao mesmo tempo (ADR-0012). Deve listar
 * cada custódia exatamente uma vez; a primeira tem maior precedência.
 */
public record PrecedenciaDeCustodia(List<Custodia> ordem) {

    /** NSF > SF > F5 > OD. */
    public static final PrecedenciaDeCustodia PADRAO =
            new PrecedenciaDeCustodia(List.of(Custodia.NSF, Custodia.SF, Custodia.F5, Custodia.OD));

    public PrecedenciaDeCustodia {
        Objects.requireNonNull(ordem, "ordem");
        ordem = List.copyOf(ordem);
        var distintas = EnumSet.noneOf(Custodia.class);
        distintas.addAll(ordem);
        if (ordem.size() != Custodia.values().length || distintas.size() != ordem.size()) {
            throw new IllegalArgumentException(
                    "A precedência de custódia deve listar cada custódia uma vez: " + ordem);
        }
    }

    /** A custódia de maior precedência entre as encontradas; vazio se nenhuma. */
    public Optional<Custodia> vencedora(Collection<Custodia> encontradas) {
        return ordem.stream().filter(encontradas::contains).findFirst();
    }
}
