package br.com.consultas.operacoescredito.config.observabilidade;

import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanFilter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * Mascara CPF/CNPJ e conta em todo atributo textual de span antes da exportação (mesmas regras dos
 * logs, {@link MascaramentoDadosPessoais}). Atua no exportador, então vale para os spans de qualquer
 * adapter, presentes e futuros, sem que cada um precise lembrar de mascarar.
 */
@Component
class MascaramentoSpanFilter implements SpanFilter {

    @Override
    public FinishedSpan map(FinishedSpan span) {
        var atributos = span.getTypedTags();
        var mascarados = new LinkedHashMap<String, Object>(atributos.size());
        var alterou = false;
        for (var atributo : atributos.entrySet()) {
            var valor = atributo.getValue();
            if (valor instanceof String texto) {
                var mascarado = MascaramentoDadosPessoais.mascarar(atributo.getKey(), texto);
                alterou |= !mascarado.equals(texto);
                valor = mascarado;
            }
            mascarados.put(atributo.getKey(), valor);
        }
        return alterou ? span.setTypedTags(mascarados) : span;
    }
}
