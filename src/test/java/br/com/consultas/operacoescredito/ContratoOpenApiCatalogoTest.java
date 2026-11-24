package br.com.consultas.operacoescredito;

import br.com.consultas.operacoescredito.domain.Bloco;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os dois arquivos de contrato (ADR-0017) não divergem entre si: cada bloco do {@code catalogo-campos.yml} tem
 * em {@code contrato/openapi.yaml} o schema {@code bloco.<nome>} com a mesma estrutura e exatamente os mesmos
 * campos, com o tipo e o {@code anulavel} do catálogo, também nos campos compostos.
 */
class ContratoOpenApiCatalogoTest {

    private static final String PREFIXO_REF = "#/components/schemas/";
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final JsonNode CONTRATO = YAML.readTree(Path.of(ContratoOpenApi.CAMINHO).toFile());
    private static final JsonNode CATALOGO =
            YAML.readTree(Path.of("src/main/resources/catalogo-campos.yml").toFile()).at("/consultas/catalogo/blocos");
    private static final JsonNode SCHEMAS = CONTRATO.at("/components/schemas");

    @Test
    void cadaBlocoDoCatalogoTemSchemaComOsMesmosCamposTiposENulidade() {
        var divergencias = new ArrayList<String>();
        for (var bloco : CATALOGO) {
            var nome = bloco.get("bloco").asString();
            var schema = SCHEMAS.get("bloco." + nome);
            if (schema == null) {
                divergencias.add("bloco " + nome + ": sem schema bloco." + nome);
                continue;
            }
            var lista = bloco.get("estrutura").asString().equals("lista");
            assertTipo(divergencias, nome, schema, lista ? "array" : "object");
            // null = bloco indisponível; operacao nunca é indisponível (sem ele a operação não existe)
            assertNulo(divergencias, nome, schema, !nome.equals(Bloco.OPERACAO.nome()));
            compararCampos(divergencias, nome, bloco.get("campos"), lista ? schema.get("items") : schema);
        }
        assertThat(divergencias).as("catalogo-campos.yml x %s", ContratoOpenApi.CAMINHO).isEmpty();
    }

    @Test
    void operacaoDoContratoTemUmaChavePorBlocoEOsSchemasSaoOsDoCatalogo() {
        var blocos = Arrays.stream(Bloco.values()).map(Bloco::nome).toList();
        assertThat(nomes(SCHEMAS.at("/Operacao/properties"))).containsExactlyElementsOf(blocos);
        assertThat(nomes(SCHEMAS).stream().filter(nome -> nome.startsWith("bloco.")).toList())
                .containsExactlyElementsOf(blocos.stream().map(nome -> "bloco." + nome).toList());
        var doCatalogo = new ArrayList<String>();
        CATALOGO.forEach(bloco -> doCatalogo.add(bloco.get("bloco").asString()));
        assertThat(doCatalogo).containsExactlyElementsOf(blocos);
    }

    @Test
    void headerDeBlocosIndisponiveisAceitaExatamenteOsNomesDosBlocos() {
        var padrao = CONTRATO.at("/components/headers/BlocosIndisponiveis/schema/pattern").asString();
        var nomes = String.join("|", Arrays.stream(Bloco.values()).map(Bloco::nome).toList());
        assertThat(padrao).isEqualTo("^(" + nomes + ")(,(" + nomes + "))*$");
    }

    private static void compararCampos(List<String> divergencias, String caminho, JsonNode campos, JsonNode objeto) {
        var propriedades = objeto == null ? null : objeto.get("properties");
        if (propriedades == null) {
            divergencias.add(caminho + ": schema sem properties");
            return;
        }
        var doCatalogo = new TreeSet<String>();
        for (var campo : campos) {
            var nome = campo.get("nome").asString();
            doCatalogo.add(nome);
            var propriedade = propriedades.get(nome);
            if (propriedade == null) {
                divergencias.add(caminho + "." + nome + ": ausente no contrato");
                continue;
            }
            compararCampo(divergencias, caminho + "." + nome, campo, resolver(propriedade));
        }
        var noContrato = new TreeSet<>(nomes(propriedades));
        noContrato.removeAll(doCatalogo);
        noContrato.forEach(nome -> divergencias.add(caminho + "." + nome + ": no contrato e fora do catálogo"));
    }

    private static void compararCampo(List<String> divergencias, String caminho, JsonNode campo, JsonNode schema) {
        var tipo = campo.get("tipo").asString();
        assertNulo(divergencias, caminho, schema, campo.path("anulavel").asBoolean(false));
        switch (tipo) {
            case "numero" -> assertTipo(divergencias, caminho, schema, "integer");
            case "decimal" -> assertTipo(divergencias, caminho, schema, "number");
            case "texto" -> assertTipo(divergencias, caminho, schema, "string");
            case "booleano" -> assertTipo(divergencias, caminho, schema, "boolean");
            case "data" -> assertFormato(divergencias, caminho, schema, "^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
            case "data_hora" -> assertFormato(divergencias, caminho, schema,
                    "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}$");
            case "objeto" -> {
                assertTipo(divergencias, caminho, schema, "object");
                compararCampos(divergencias, caminho, campo.get("campos"), schema);
            }
            case "lista" -> {
                assertTipo(divergencias, caminho, schema, "array");
                compararCampos(divergencias, caminho, campo.get("campos"), schema.get("items"));
            }
            default -> divergencias.add(caminho + ": tipo do catálogo desconhecido " + tipo);
        }
    }

    private static void assertFormato(List<String> divergencias, String caminho, JsonNode schema, String padrao) {
        assertTipo(divergencias, caminho, schema, "string");
        if (!padrao.equals(schema.path("pattern").asString(null))) {
            divergencias.add(caminho + ": pattern " + schema.path("pattern").asString(null) + ", esperado " + padrao);
        }
    }

    private static void assertTipo(List<String> divergencias, String caminho, JsonNode schema, String tipo) {
        var noContrato = schema.path("type").asString(null);
        if (!tipo.equals(noContrato)) {
            divergencias.add(caminho + ": type " + noContrato + ", esperado " + tipo);
        }
    }

    private static void assertNulo(List<String> divergencias, String caminho, JsonNode schema, boolean anulavel) {
        if (schema.path("nullable").asBoolean(false) != anulavel) {
            divergencias.add(caminho + ": nullable deveria ser " + anulavel);
        }
    }

    private static JsonNode resolver(JsonNode schema) {
        var ref = schema.path("$ref").asString(null);
        return ref == null ? schema : SCHEMAS.get(ref.substring(PREFIXO_REF.length()));
    }

    private static Set<String> nomes(JsonNode objeto) {
        var nomes = new LinkedHashSet<String>();
        objeto.properties().forEach(propriedade -> nomes.add(propriedade.getKey()));
        return nomes;
    }
}
