package br.com.consultas.operacoescredito.application.port.out;

import br.com.consultas.operacoescredito.domain.Custodia;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alguma custódia não pôde ser consultada e, sem ela, a resposta não seria confiável (ADR-0012, ADR-0013): a
 * operação não foi achada nas demais (pode estar justamente na indisponível) ou a lista de operações de um cliente
 * ou conta ficaria incompleta. Vira 503 com as custódias no Problem Details, nunca 404 nem lista parcial.
 */
public class CustodiasIndisponiveisException extends FonteDeOperacoesIndisponivelException {

    private final Set<Custodia> custodias;

    public CustodiasIndisponiveisException(Set<Custodia> custodias, Duration tenteNovamenteEm, Throwable causa) {
        super("Custódias indisponíveis: " + codigos(custodias), tenteNovamenteEm, causa);
        this.custodias = Collections.unmodifiableSet(EnumSet.copyOf(custodias));
    }

    /** Custódias que não puderam ser consultadas, na ordem canônica. */
    public Set<Custodia> custodias() {
        return custodias;
    }

    private static String codigos(Set<Custodia> custodias) {
        return custodias.stream().map(Custodia::codigo).collect(Collectors.joining(","));
    }
}
