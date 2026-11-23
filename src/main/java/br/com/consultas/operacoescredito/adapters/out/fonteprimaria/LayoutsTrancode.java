package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.domain.Bloco;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

/**
 * Layouts de trancode dos 9 blocos, um arquivo {@code <bloco>.yml} por bloco no diretório configurado.
 * Carregados e validados na subida: layout ausente ou inconsistente impede a aplicação de iniciar.
 */
final class LayoutsTrancode {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final Map<Bloco, LayoutTrancode> layouts;

    private LayoutsTrancode(Map<Bloco, LayoutTrancode> layouts) {
        this.layouts = layouts;
    }

    /** @param diretorio local dos layouts (ex.: {@code classpath:trancode/layouts/}) */
    static LayoutsTrancode carregar(ResourceLoader resourceLoader, String diretorio) {
        var base = diretorio.endsWith("/") ? diretorio : diretorio + "/";
        var layouts = new EnumMap<Bloco, LayoutTrancode>(Bloco.class);
        for (var bloco : Bloco.values()) {
            var recurso = resourceLoader.getResource(base + bloco.nome() + ".yml");
            try (var entrada = recurso.getInputStream()) {
                var layout = YAML.readValue(entrada, LayoutTrancode.class);
                validar(bloco, layout);
                layouts.put(bloco, layout);
            } catch (IOException | JacksonException e) {
                throw new LayoutTrancodeInvalidoException(
                        "Não foi possível ler o layout de trancode " + recurso.getDescription(), e);
            }
        }
        return new LayoutsTrancode(layouts);
    }

    LayoutTrancode de(Bloco bloco) {
        return layouts.get(bloco);
    }

    static void validar(Bloco bloco, LayoutTrancode layout) {
        if (!bloco.nome().equals(layout.bloco())) {
            throw invalido(bloco, "declara bloco '" + layout.bloco() + "'");
        }
        if (layout.estrutura() == null || layout.registro() == null) {
            throw invalido(bloco, "exige estrutura e registro");
        }
        validarRegistro(bloco, layout.bloco(), layout.registro());
    }

    private static void validarRegistro(Bloco bloco, String caminho, LayoutTrancode.Registro registro) {
        if (registro.campos().isEmpty()) {
            throw invalido(bloco, caminho + " não declara campos");
        }
        var ordenados = new ArrayList<>(registro.campos());
        ordenados.sort(Comparator.comparingInt(LayoutTrancode.Campo::inicio));
        var fimAnterior = 0;
        for (var campo : ordenados) {
            var nome = caminho + "." + campo.campo();
            if (campo.campo() == null || campo.campo().isBlank() || campo.tipo() == null) {
                throw invalido(bloco, caminho + " tem campo sem nome ou sem tipo");
            }
            if (campo.inicio() < 1 || campo.tamanho() < 1) {
                throw invalido(bloco, nome + " exige inicio e tamanho positivos");
            }
            if (campo.inicio() <= fimAnterior) {
                throw invalido(bloco, nome + " sobrepõe o campo anterior (posição " + campo.inicio() + ")");
            }
            try {
                campo.tipo().validarFormato(campo.formato());
            } catch (RuntimeException e) {
                throw invalido(bloco, nome + " tem formato inválido: " + e.getMessage());
            }
            fimAnterior = campo.fim();
        }
        for (var lista : registro.listas()) {
            if (lista.registro() == null) {
                throw invalido(bloco, caminho + "." + lista.campo() + " exige registro");
            }
            validarRegistro(bloco, caminho + "." + lista.campo(), lista.registro());
        }
    }

    private static LayoutTrancodeInvalidoException invalido(Bloco bloco, String motivo) {
        return new LayoutTrancodeInvalidoException("Layout de trancode do bloco " + bloco.nome() + ": " + motivo);
    }

    static class LayoutTrancodeInvalidoException extends RuntimeException {

        LayoutTrancodeInvalidoException(String mensagem) {
            super(mensagem);
        }

        LayoutTrancodeInvalidoException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }
}
