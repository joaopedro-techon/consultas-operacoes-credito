package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste isolado porque a escala e a precisão do {@code BigDecimal} (75.0 vs 75) e a rejeição de payload
 * corrompido não são observáveis de forma confiável pela API HTTP (comparações JSON tratam números como double).
 */
class TextoDetalheCodecTest {

    private final TextoDetalheCodec codec = new TextoDetalheCodec();

    @Test
    void decimaisSaoLidosComoBigDecimalSemPerderEscalaNemPrecisao() throws IOException {
        var conteudo = codec.decodificar(gzip("""
                [{"id_operacao":100000001,"valor_componente_financeiro":75.0,"taxa":0.1}]"""));

        assertThat(conteudo).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var item = ((List<Map<String, Object>>) conteudo).getFirst();
        assertThat(item.get("valor_componente_financeiro")).isEqualTo(new BigDecimal("75.0"));
        assertThat(item.get("taxa")).isEqualTo(new BigDecimal("0.1"));
        assertThat(item.get("id_operacao")).isEqualTo(100000001);
    }

    @Test
    void blocoObjetoViraMapComNulosPreservados() throws IOException {
        var conteudo = codec.decodificar(gzip("""
                {"id_operacao":100000001,"data_baixa_operacao":null}"""));

        assertThat(conteudo).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id_operacao", 100000001)
                .containsEntry("data_baixa_operacao", null);
    }

    @Test
    void conteudoQueNaoEhGzipEhRejeitado() {
        var bytes = "{\"id_operacao\":1}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> codec.decodificar(bytes))
                .isInstanceOf(TextoDetalheCodec.TextoDetalheInvalidoException.class);
    }

    @Test
    void gzipComJsonInvalidoEhRejeitado() throws IOException {
        var bytes = gzip("{\"id_operacao\":");

        assertThatThrownBy(() -> codec.decodificar(bytes))
                .isInstanceOf(TextoDetalheCodec.TextoDetalheInvalidoException.class);
    }

    private static byte[] gzip(String json) throws IOException {
        var saida = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(saida)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return saida.toByteArray();
    }
}
