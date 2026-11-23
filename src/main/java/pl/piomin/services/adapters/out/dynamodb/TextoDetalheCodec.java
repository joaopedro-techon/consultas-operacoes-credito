package pl.piomin.services.adapters.out.dynamodb;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Converte o atributo {@code texto_detalhe} (JSON do bloco em GZIP) no conteúdo do bloco:
 * {@code Map<String, Object>} ou {@code List<Map<String, Object>>}, com decimais como {@code BigDecimal}.
 */
class TextoDetalheCodec {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    Object decodificar(byte[] textoDetalhe) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(textoDetalhe))) {
            return jsonMapper.readValue(gzip, Object.class);
        } catch (IOException | JacksonException e) {
            throw new TextoDetalheInvalidoException(e);
        }
    }

    static class TextoDetalheInvalidoException extends RuntimeException {
        TextoDetalheInvalidoException(Exception causa) {
            super("texto_detalhe não é um JSON GZIP válido", causa);
        }
    }
}
