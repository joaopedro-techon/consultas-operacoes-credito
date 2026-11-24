package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import br.com.consultas.operacoescredito.domain.catalogo.TipoCampo;
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
 * Layouts de trancode por custódia: os 9 blocos ({@code <bloco>.yml}) e a lista de operações de um cliente ou conta
 * ({@value #LISTA_OPERACOES}{@code .yml}). Cada custódia pode ter o seu layout de um bloco em
 * {@code <diretório>/<CUSTODIA>/<bloco>.yml} (campos a mais, a menos ou em outras posições); sem ele, vale o layout
 * base do diretório. Todos produzem o mesmo contrato: cada campo não {@code ignorar} precisa existir no catálogo de
 * campos do bloco ({@link #validarContra}).
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

    private final Map<Custodia, Map<Bloco, LayoutTrancode>> layouts;
    private final Map<Custodia, LayoutTrancode> listasOperacoes;

    private LayoutsTrancode(Map<Custodia, Map<Bloco, LayoutTrancode>> layouts,
                            Map<Custodia, LayoutTrancode> listasOperacoes) {
        this.layouts = layouts;
        this.listasOperacoes = listasOperacoes;
    }

    /** @param diretorio local dos layouts (ex.: {@code classpath:trancode/layouts/}) */
    static LayoutsTrancode carregar(ResourceLoader resourceLoader, String diretorio) {
        var base = diretorio.endsWith("/") ? diretorio : diretorio + "/";
        var layouts = new EnumMap<Custodia, Map<Bloco, LayoutTrancode>>(Custodia.class);
        var listas = new EnumMap<Custodia, LayoutTrancode>(Custodia.class);
        var basePorNome = new HashMap<String, LayoutTrancode>();
        for (var bloco : Bloco.values()) {
            basePorNome.put(bloco.nome(), ler(resourceLoader, base, bloco.nome()));
        }
        basePorNome.put(LISTA_OPERACOES, ler(resourceLoader, base, LISTA_OPERACOES));
        for (var custodia : Custodia.values()) {
            var daCustodia = new EnumMap<Bloco, LayoutTrancode>(Bloco.class);
            for (var bloco : Bloco.values()) {
                daCustodia.put(bloco, daCustodia(resourceLoader, base, custodia, bloco.nome(), basePorNome));
            }
            var lista = daCustodia(resourceLoader, base, custodia, LISTA_OPERACOES, basePorNome);
            validarListaOperacoes(custodia, lista);
            layouts.put(custodia, daCustodia);
            listas.put(custodia, lista);
        }
        return new LayoutsTrancode(layouts, listas);
    }

    LayoutTrancode de(Custodia custodia, Bloco bloco) {
        return layouts.get(custodia).get(bloco);
    }

    /** Layout da lista de operações de um cliente ou conta ({@code id_operacao} + data de contratação). */
    LayoutTrancode listaOperacoes(Custodia custodia) {
        return listasOperacoes.get(custodia);
    }

    /**
     * Todo campo dos layouts que não é {@code ignorar} precisa existir no catálogo do bloco, com a mesma estrutura
     * (objeto/lista) e as sub-listas como campos {@code lista}: o trancode de qualquer custódia vira o mesmo
     * contrato.
     */
    void validarContra(CatalogoDeCampos catalogo) {
        layouts.forEach((custodia, porBloco) -> porBloco.forEach((bloco, layout) -> {
            var definicao = catalogo.definicao(bloco);
            var esperada = definicao.estrutura() == EstruturaBloco.LISTA
                    ? LayoutTrancode.Estrutura.LISTA
                    : LayoutTrancode.Estrutura.OBJETO;
            var nome = custodia.codigo() + "/" + bloco.nome();
            if (layout.estrutura() != esperada) {
                throw invalido(nome, "tem estrutura " + layout.estrutura() + ", o catálogo diz " + esperada);
            }
            validarCampos(nome, catalogo, bloco, "", layout.registro());
        }));
    }

    private static void validarCampos(String nome, CatalogoDeCampos catalogo, Bloco bloco, String prefixo,
                                      LayoutTrancode.Registro registro) {
        for (var campo : registro.campos()) {
            if (!campo.ignorar() && catalogo.campo(bloco, prefixo + campo.campo()).isEmpty()) {
                throw invalido(nome, "campo " + prefixo + campo.campo() + " não está no catálogo de campos (marque "
                        + "ignorar: true se ele não faz parte do contrato)");
            }
        }
        for (var lista : registro.listas()) {
            var noCatalogo = catalogo.campo(bloco, prefixo + lista.campo());
            if (noCatalogo.isEmpty() || noCatalogo.get().tipo() != TipoCampo.LISTA) {
                throw invalido(nome, "sub-lista " + prefixo + lista.campo() + " não é um campo lista no catálogo");
            }
            validarCampos(nome, catalogo, bloco, prefixo + lista.campo() + ".", lista.registro());
        }
    }

    private static LayoutTrancode daCustodia(ResourceLoader resourceLoader, String base, Custodia custodia,
                                             String nome, Map<String, LayoutTrancode> basePorNome) {
        var override = base + custodia.codigo() + "/";
        var padrao = basePorNome.get(nome);
        if (!resourceLoader.getResource(override + nome + ".yml").exists()) {
            return padrao;
        }
        var daCustodia = ler(resourceLoader, override, nome);
        return new LayoutTrancode(daCustodia.bloco(), daCustodia.estrutura(),
                naOrdemDe(daCustodia.registro(), padrao.registro()));
    }

    /**
     * Campos do override na ordem do layout base, que é a do contrato: o parser lê cada campo de posição fixa pela
     * posição, mas devolve as chaves na ordem da lista, e a custódia com campos em outras posições não pode mudar o
     * JSON. Campos que o base não tem (os {@code ignorar}) ficam no fim. As sub-listas mantêm a ordem do override,
     * porque são lidas em sequência no trancode; os campos de cada uma seguem o base.
     */
    private static LayoutTrancode.Registro naOrdemDe(LayoutTrancode.Registro registro, LayoutTrancode.Registro base) {
        var ordem = new HashMap<String, Integer>();
        for (int i = 0; i < base.campos().size(); i++) {
            ordem.put(base.campos().get(i).campo(), i);
        }
        var campos = new ArrayList<>(registro.campos());
        campos.sort(Comparator.comparingInt(campo -> ordem.getOrDefault(campo.campo(), Integer.MAX_VALUE)));
        var listasDoBase = new HashMap<String, LayoutTrancode.Lista>();
        base.listas().forEach(lista -> listasDoBase.put(lista.campo(), lista));
        var listas = new ArrayList<LayoutTrancode.Lista>();
        for (var lista : registro.listas()) {
            var doBase = listasDoBase.get(lista.campo());
            listas.add(doBase == null ? lista
                    : new LayoutTrancode.Lista(lista.campo(), naOrdemDe(lista.registro(), doBase.registro())));
        }
        return new LayoutTrancode.Registro(campos, listas);
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

    /** A lista precisa ser paginada, com o id numérico e a data de contratação de cada operação. */
    private static void validarListaOperacoes(Custodia custodia, LayoutTrancode layout) {
        var nome = custodia.codigo() + "/" + LISTA_OPERACOES;
        if (layout.estrutura() != LayoutTrancode.Estrutura.LISTA) {
            throw invalido(nome, "exige estrutura lista");
        }
        var tipos = new HashMap<String, TipoCampoTrancode>();
        layout.registro().campos().stream()
                .filter(campo -> !campo.ignorar())
                .forEach(campo -> tipos.put(campo.campo(), campo.tipo()));
        if (tipos.get(CAMPO_ID_OPERACAO) != TipoCampoTrancode.NUMERO) {
            throw invalido(nome, "exige o campo " + CAMPO_ID_OPERACAO + " do tipo numero");
        }
        if (tipos.get(CAMPO_DATA_CONTRATACAO) != TipoCampoTrancode.DATA) {
            throw invalido(nome, "exige o campo " + CAMPO_DATA_CONTRATACAO + " do tipo data");
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
