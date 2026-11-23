package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.catalogo.Campo;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada payload da massa local ({@code localstack/massa}) usa exatamente os campos e tipos do catálogo
 * de campos: nenhum campo fora do catálogo, nenhum campo do catálogo faltando, valores do tipo declarado.
 */
class CatalogoDeCamposMassaTest {

    private static final Path MASSA = Path.of("localstack", "massa");
    private static final Pattern DATA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATA_HORA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @TestFactory
    Stream<DynamicTest> payloadsDaMassaSeguemOCatalogo() throws IOException {
        var catalogo = catalogo();
        var arquivos = new ArrayList<Path>();
        try (var caminhos = Files.walk(MASSA)) {
            caminhos.filter(caminho -> caminho.toString().endsWith(".json")).sorted().forEach(arquivos::add);
        }
        assertThat(arquivos).isNotEmpty();
        return arquivos.stream().map(arquivo -> DynamicTest.dynamicTest(MASSA.relativize(arquivo).toString(), () -> {
            var bloco = Bloco.valueOf(arquivo.getFileName().toString().replace(".json", "").toUpperCase(Locale.ROOT));
            var definicao = catalogo.definicao(bloco);
            var conteudo = jsonMapper.readValue(arquivo.toFile(), Object.class);
            var erros = new ArrayList<String>();
            if (definicao.estrutura() == EstruturaBloco.LISTA) {
                assertThat(conteudo).isInstanceOf(List.class);
                ((List<?>) conteudo).forEach(item -> verificarObjeto(item, definicao.campos(), bloco.nome(), erros));
            } else {
                verificarObjeto(conteudo, definicao.campos(), bloco.nome(), erros);
            }
            assertThat(erros).isEmpty();
        }));
    }

    private static void verificarObjeto(Object valor, List<Campo> campos, String caminho, List<String> erros) {
        if (!(valor instanceof Map<?, ?> objeto)) {
            erros.add(caminho + ": esperado objeto");
            return;
        }
        objeto.keySet().stream()
                .filter(chave -> campos.stream().noneMatch(campo -> campo.nome().equals(chave)))
                .forEach(chave -> erros.add(caminho + "." + chave + ": fora do catálogo"));
        for (var campo : campos) {
            var caminhoCampo = caminho + "." + campo.nome();
            if (!objeto.containsKey(campo.nome())) {
                erros.add(caminhoCampo + ": ausente");
                continue;
            }
            verificarValor(objeto.get(campo.nome()), campo, caminhoCampo, erros);
        }
    }

    private static void verificarValor(Object valor, Campo campo, String caminho, List<String> erros) {
        if (valor == null) {
            if (!campo.anulavel()) {
                erros.add(caminho + ": nulo, mas não é anulável");
            }
            return;
        }
        var valido = switch (campo.tipo()) {
            case NUMERO -> valor instanceof Integer || valor instanceof Long;
            case DECIMAL -> valor instanceof BigDecimal;
            case DATA -> valor instanceof String texto && DATA.matcher(texto).matches();
            case DATA_HORA -> valor instanceof String texto && DATA_HORA.matcher(texto).matches();
            case TEXTO -> valor instanceof String;
            case BOOLEANO -> valor instanceof Boolean;
            case OBJETO -> {
                verificarObjeto(valor, campo.campos(), caminho, erros);
                yield true;
            }
            case LISTA -> {
                if (valor instanceof List<?> itens) {
                    itens.forEach(item -> verificarObjeto(item, campo.campos(), caminho + "[]", erros));
                    yield true;
                }
                yield false;
            }
        };
        if (!valido) {
            erros.add(caminho + ": valor " + valor + " não é do tipo " + campo.tipo());
        }
    }

    private static CatalogoDeCampos catalogo() throws IOException {
        var fontes = new YamlPropertySourceLoader().load("catalogo", new ClassPathResource("catalogo-campos.yml"));
        return new Binder(ConfigurationPropertySources.from(fontes))
                .bind("consultas.catalogo", CatalogoDeCamposProperties.class)
                .orElseThrow(() -> new IllegalStateException("consultas.catalogo ausente"))
                .paraCatalogo();
    }
}
