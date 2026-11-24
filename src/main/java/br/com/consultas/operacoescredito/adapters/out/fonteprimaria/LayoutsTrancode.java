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
import java.util.HashMap;
import java.util.Map;

/**
 * Layouts de trancode dos 9 blocos, um arquivo {@code <bloco>.yml} por bloco no diretório configurado, e o da
 * lista de operações de um cliente ou conta ({@value #LISTA_OPERACOES}{@code .yml}).
 * Carregados e validados na subida: layout ausente ou inconsistente impede a aplicação de iniciar.
 */
final class LayoutsTrancode {

    /** Nome do layout da lista de operações de um cliente ou conta. */
    static final String LISTA_OPERACOES = "lista_operacoes";
    static final String CAMPO_ID_OPERACAO = "id_operacao";
    static final String CAMPO_DATA_CONTRATACAO = "data_contratacao_operacao";

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final Map<Bloco, LayoutTrancode> layouts;
    private final LayoutTrancode listaOperacoes;

    private LayoutsTrancode(Map<Bloco, LayoutTrancode> layouts, LayoutTrancode listaOperacoes) {
        this.layouts = layouts;
        this.listaOperacoes = listaOperacoes;
    }

    /** @param diretorio local dos layouts (ex.: {@code classpath:trancode/layouts/}) */
    static LayoutsTrancode carregar(ResourceLoader resourceLoader, String diretorio) {
        var base = diretorio.endsWith("/") ? diretorio : diretorio + "/";
        var layouts = new EnumMap<Bloco, LayoutTrancode>(Bloco.class);
        for (var bloco : Bloco.values()) {
            layouts.put(bloco, ler(resourceLoader, base, bloco.nome()));
        }
        var listaOperacoes = ler(resourceLoader, base, LISTA_OPERACOES);
        validarListaOperacoes(listaOperacoes);
        return new LayoutsTrancode(layouts, listaOperacoes);
    }

    LayoutTrancode de(Bloco bloco) {
        return layouts.get(bloco);
    }

    /** Layout da lista de operações de um cliente ou conta ({@code id_operacao} + data de contratação). */
    LayoutTrancode listaOperacoes() {
        return listaOperacoes;
    }

    private static LayoutTrancode ler(ResourceLoader resourceLoader, String base, String nome) {
        var recurso = resourceLoader.getResource(base + nome + ".yml");
        try (var entrada = recurso.getInputStream()) {
            var layout = YAML.readValue(entrada, LayoutTrancode.class);
            validar(nome, layout);
            return layout;
        } catch (IOException | JacksonException e) {
            throw new LayoutTrancodeInvalidoException(
                    "Não foi possível ler o layout de trancode " + recurso.getDescription(), e);
        }
    }

    static void validar(String nome, LayoutTrancode layout) {
        if (!nome.equals(layout.bloco())) {
            throw invalido(nome, "declara bloco '" + layout.bloco() + "'");
        }
        if (layout.estrutura() == null || layout.registro() == null) {
            throw invalido(nome, "exige estrutura e registro");
        }
        validarRegistro(nome, layout.bloco(), layout.registro());
    }

    /** A lista precisa ser um bloco-lista com o id numérico e a data de contratação de cada operação. */
    private static void validarListaOperacoes(LayoutTrancode layout) {
        if (layout.estrutura() != LayoutTrancode.Estrutura.LISTA) {
            throw invalido(LISTA_OPERACOES, "exige estrutura lista");
        }
        var tipos = new HashMap<String, TipoCampoTrancode>();
        layout.registro().campos().forEach(campo -> tipos.put(campo.campo(), campo.tipo()));
        if (tipos.get(CAMPO_ID_OPERACAO) != TipoCampoTrancode.NUMERO) {
            throw invalido(LISTA_OPERACOES, "exige o campo " + CAMPO_ID_OPERACAO + " do tipo numero");
        }
        if (tipos.get(CAMPO_DATA_CONTRATACAO) != TipoCampoTrancode.DATA) {
            throw invalido(LISTA_OPERACOES, "exige o campo " + CAMPO_DATA_CONTRATACAO + " do tipo data");
        }
    }

    private static void validarRegistro(String nome, String caminho, LayoutTrancode.Registro registro) {
        if (registro.campos().isEmpty()) {
            throw invalido(nome, caminho + " não declara campos");
        }
        var ordenados = new ArrayList<>(registro.campos());
        ordenados.sort(Comparator.comparingInt(LayoutTrancode.Campo::inicio));
        var fimAnterior = 0;
        for (var campo : ordenados) {
            var nomeCampo = caminho + "." + campo.campo();
            if (campo.campo() == null || campo.campo().isBlank() || campo.tipo() == null) {
                throw invalido(nome, caminho + " tem campo sem nome ou sem tipo");
            }
            if (campo.inicio() < 1 || campo.tamanho() < 1) {
                throw invalido(nome, nomeCampo + " exige inicio e tamanho positivos");
            }
            if (campo.inicio() <= fimAnterior) {
                throw invalido(nome, nomeCampo + " sobrepõe o campo anterior (posição " + campo.inicio() + ")");
            }
            try {
                campo.tipo().validarFormato(campo.formato());
            } catch (RuntimeException e) {
                throw invalido(nome, nomeCampo + " tem formato inválido: " + e.getMessage());
            }
            fimAnterior = campo.fim();
        }
        for (var lista : registro.listas()) {
            if (lista.registro() == null) {
                throw invalido(nome, caminho + "." + lista.campo() + " exige registro");
            }
            validarRegistro(nome, caminho + "." + lista.campo(), lista.registro());
        }
    }

    private static LayoutTrancodeInvalidoException invalido(String nome, String motivo) {
        return new LayoutTrancodeInvalidoException("Layout de trancode " + nome + ": " + motivo);
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
