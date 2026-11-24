package br.com.consultas.operacoescredito.config.observabilidade;

import br.com.consultas.operacoescredito.application.port.out.ObservadorDeEtapas;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;
import java.util.function.Supplier;

/** Etapas da camada de aplicação como observações {@link Observacoes#etapa}. */
public final class ObservadorDeEtapasMicrometer implements ObservadorDeEtapas {

    private final ObservationRegistry registry;

    public ObservadorDeEtapasMicrometer(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public <T> T observar(String etapa, Supplier<T> passo) {
        return Observacoes.etapa(registry, etapa).observe(passo);
    }
}
