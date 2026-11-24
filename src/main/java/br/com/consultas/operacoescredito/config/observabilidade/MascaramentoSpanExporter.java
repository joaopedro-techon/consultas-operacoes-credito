package br.com.consultas.operacoescredito.config.observabilidade;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Exportador que mascara CPF/CNPJ e conta (mesmas regras dos logs, {@link MascaramentoDadosPessoais}) em tudo o que
 * um span leva de texto antes de entregá-lo ao exportador real: nome, atributos, eventos (nome e atributos, como
 * {@code exception.message} e {@code exception.stacktrace} de uma exceção registrada) e descrição do status.
 * Uma exceção do {@code RestClient} traz a URL do gateway ({@code /mainframe/clientes/<cpf_cnpj>/operacoes},
 * {@code /contas/<conta>}) na mensagem e no stack trace, e os spans de erro (do adapter, do cliente HTTP e do
 * servidor) não podem levá-la em claro. Envolve todo {@link SpanExporter} da aplicação
 * ({@link MascaramentoSpanConfig}), então vale para os spans de qualquer instrumentação, presente ou futura.
 */
final class MascaramentoSpanExporter implements SpanExporter {

    private final SpanExporter exportador;

    MascaramentoSpanExporter(SpanExporter exportador) {
        this.exportador = Objects.requireNonNull(exportador, "exportador");
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        var mascarados = new ArrayList<SpanData>(spans.size());
        for (var span : spans) {
            mascarados.add(mascarar(span));
        }
        return exportador.export(mascarados);
    }

    @Override
    public CompletableResultCode flush() {
        return exportador.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return exportador.shutdown();
    }

    @Override
    public String toString() {
        return "MascaramentoSpanExporter{" + exportador + "}";
    }

    static SpanData mascarar(SpanData span) {
        return new SpanMascarado(span);
    }

    static Attributes mascarar(Attributes atributos) {
        if (atributos.isEmpty()) {
            return atributos;
        }
        AttributesBuilder mascarados = Attributes.builder();
        atributos.forEach((chave, valor) -> colocar(mascarados, chave, valor));
        return mascarados.build();
    }

    @SuppressWarnings("unchecked")
    private static void colocar(AttributesBuilder destino, AttributeKey<?> chave, Object valor) {
        if (chave.getType() == AttributeType.STRING) {
            destino.put((AttributeKey<String>) chave, MascaramentoDadosPessoais.mascarar(chave.getKey(), (String) valor));
        } else if (chave.getType() == AttributeType.STRING_ARRAY) {
            var textos = (List<String>) valor;
            var mascarados = new ArrayList<String>(textos.size());
            for (var texto : textos) {
                mascarados.add(MascaramentoDadosPessoais.mascarar(chave.getKey(), texto));
            }
            destino.put((AttributeKey<List<String>>) chave, mascarados);
        } else {
            destino.put((AttributeKey<Object>) chave, valor);
        }
    }

    /** Span com os textos mascarados; o resto (ids, tempos, recurso, links) vem do original. */
    private static final class SpanMascarado extends DelegatingSpanData {

        private final String nome;
        private final Attributes atributos;
        private final List<EventData> eventos;
        private final StatusData status;

        SpanMascarado(SpanData original) {
            super(original);
            this.nome = MascaramentoDadosPessoais.mascarar(original.getName());
            this.atributos = MascaramentoSpanExporter.mascarar(original.getAttributes());
            this.eventos = original.getEvents().stream().map(SpanMascarado::mascarar).toList();
            this.status = mascarar(original.getStatus());
        }

        private static EventData mascarar(EventData evento) {
            return EventData.create(evento.getEpochNanos(), MascaramentoDadosPessoais.mascarar(evento.getName()),
                    MascaramentoSpanExporter.mascarar(evento.getAttributes()), evento.getTotalAttributeCount());
        }

        private static StatusData mascarar(StatusData status) {
            var descricao = status.getDescription();
            var mascarada = MascaramentoDadosPessoais.mascarar(descricao);
            return Objects.equals(descricao, mascarada) ? status : StatusData.create(status.getStatusCode(), mascarada);
        }

        @Override
        public String getName() {
            return nome;
        }

        @Override
        public Attributes getAttributes() {
            return atributos;
        }

        @Override
        public List<EventData> getEvents() {
            return eventos;
        }

        @Override
        public StatusData getStatus() {
            return status;
        }
    }
}
