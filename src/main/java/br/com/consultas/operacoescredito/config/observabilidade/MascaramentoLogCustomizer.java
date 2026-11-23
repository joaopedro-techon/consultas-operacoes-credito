package br.com.consultas.operacoescredito.config.observabilidade;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Aplica {@link MascaramentoDadosPessoais} a todo valor textual do log estruturado (mensagem, MDC,
 * pares chave-valor e stack trace), para que nenhum log saia com CPF/CNPJ ou conta em claro.
 * Registrado em {@code logging.structured.json.customizer}.
 */
public class MascaramentoLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(new MascararTexto());
    }

    /** Usa o nome do campo, para mascarar {@code cpf_cnpj} e {@code conta} mesmo sem contexto no texto. */
    private static final class MascararTexto implements JsonWriter.ValueProcessor<Object> {

        @Override
        public Object processValue(JsonWriter.MemberPath path, Object value) {
            if (value instanceof CharSequence texto) {
                return MascaramentoDadosPessoais.mascarar(path == null ? null : path.name(), texto.toString());
            }
            return value;
        }
    }
}
