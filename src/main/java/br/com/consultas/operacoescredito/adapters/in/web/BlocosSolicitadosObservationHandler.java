package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.Bloco;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Contador {@code consultas.blocos}: um incremento por bloco solicitado em cada consulta, com as tags
 * {@code bloco}, {@code consumidor}, {@code tipo_consulta}, {@code rota} e {@code status} da requisição
 * (as mesmas de {@code http.server.requests}, calculadas pela {@link ConsultaObservationConvention}).
 * Só entram nomes do catálogo de blocos, para que um {@code expand} qualquer não vire tag.
 */
@Component
class BlocosSolicitadosObservationHandler implements ObservationHandler<ServerRequestObservationContext> {

    static final String METRICA = "consultas.blocos";
    private static final String PREFIXO_CONSULTA = "/v1/operacoes-credito";
    private static final Set<String> TAGS_DA_REQUISICAO = Set.of(ConsultaObservationConvention.CONSUMIDOR,
            ConsultaObservationConvention.TIPO_CONSULTA, ConsultaObservationConvention.ROTA, "status");
    private static final Set<String> CATALOGO = Arrays.stream(Bloco.values())
            .map(Bloco::nome)
            .collect(Collectors.toUnmodifiableSet());

    private final ObjectProvider<MeterRegistry> meterRegistry;

    BlocosSolicitadosObservationHandler(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        var request = context.getCarrier();
        if (!request.getRequestURI().startsWith(request.getContextPath() + PREFIXO_CONSULTA)) {
            return;
        }
        var registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        var tags = context.getLowCardinalityKeyValues().stream()
                .filter(keyValue -> TAGS_DA_REQUISICAO.contains(keyValue.getKey()))
                .toList();
        ContextoRequisicaoFilter.blocos(request).stream()
                .filter(CATALOGO::contains)
                .forEach(bloco -> {
                    var contador = Counter.builder(METRICA)
                            .description("Blocos solicitados nas consultas")
                            .tag("bloco", bloco);
                    for (KeyValue tag : tags) {
                        contador.tag(tag.getKey(), tag.getValue());
                    }
                    contador.register(registry).increment();
                });
    }
}
