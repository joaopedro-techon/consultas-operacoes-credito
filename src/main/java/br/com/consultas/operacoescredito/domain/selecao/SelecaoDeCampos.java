package br.com.consultas.operacoescredito.domain.selecao;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.Campo;
import br.com.consultas.operacoescredito.domain.catalogo.DefinicaoBloco;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Seleção de campos ({@code fields}): os campos que o consumidor quer ver em cada bloco, no formato
 * {@code bloco.campo}, com ponto também para descer em campos compostos
 * (ex.: {@code saldo.saldo_parcelas.saldo_devedor}).
 *
 * <ul>
 *   <li>Bloco sem campos selecionados volta completo.</li>
 *   <li>{@code id_operacao} volta sempre, em todo item de todo nível em que existir; um bloco cuja
 *       seleção exclui todos os demais campos volta só com ele.</li>
 *   <li>Selecionar um campo composto inteiro (ex.: {@code saldo.saldo_parcelas}) traz todos os seus subcampos.</li>
 * </ul>
 *
 * A seleção é validada contra o catálogo de campos e os blocos pedidos na criação, e aplicada sobre o
 * {@code Map} de cada bloco depois da leitura, qualquer que seja a fonte.
 */
public final class SelecaoDeCampos {

    private static final SelecaoDeCampos NENHUMA = new SelecaoDeCampos(Map.of());

    private final Map<Bloco, No> porBloco;

    private SelecaoDeCampos(Map<Bloco, No> porBloco) {
        this.porBloco = porBloco;
    }

    /** Seleção vazia: todos os blocos voltam completos. */
    public static SelecaoDeCampos nenhuma() {
        return NENHUMA;
    }

    /**
     * Valida e monta a seleção.
     *
     * @param caminhos      itens do {@code fields}, cada um {@code bloco.campo[.subcampo...]}
     * @param blocosPedidos blocos da consulta (incluindo os que vêm sempre); campo de outro bloco é inválido
     * @param catalogo      catálogo que declara os campos válidos de cada bloco
     * @throws SelecaoDeCamposInvalidaException se algum item for malformado, citar bloco desconhecido ou fora
     *         dos pedidos, ou campo fora do catálogo
     */
    public static SelecaoDeCampos de(Collection<String> caminhos, Set<Bloco> blocosPedidos, CatalogoDeCampos catalogo) {
        if (caminhos.isEmpty()) {
            return NENHUMA;
        }
        var porBloco = new EnumMap<Bloco, No>(Bloco.class);
        for (var caminho : caminhos) {
            var separador = caminho.indexOf('.');
            if (separador <= 0 || separador == caminho.length() - 1) {
                throw new SelecaoDeCamposInvalidaException(
                        "Item '" + caminho + "' inválido no parâmetro fields. Formato esperado: bloco.campo");
            }
            var nomeBloco = caminho.substring(0, separador);
            var caminhoCampo = caminho.substring(separador + 1);
            var bloco = Bloco.porNome(nomeBloco).orElseThrow(() -> blocoDesconhecido(nomeBloco));
            if (!blocosPedidos.contains(bloco)) {
                throw new SelecaoDeCamposInvalidaException("Bloco '" + nomeBloco + "' citado no parâmetro fields "
                        + "não está no expand. Inclua-o no expand para selecionar seus campos");
            }
            var definicao = catalogo.definicao(bloco);
            if (definicao.campo(caminhoCampo).isEmpty()) {
                throw campoDesconhecido(caminho, definicao);
            }
            porBloco.computeIfAbsent(bloco, b -> new No()).adicionar(caminhoCampo.split("\\.", -1), 0);
        }
        return new SelecaoDeCampos(porBloco);
    }

    /** Aplica a seleção aos blocos da operação; blocos indisponíveis ({@code null}) continuam {@code null}. */
    public OperacaoConsultada aplicar(OperacaoConsultada operacao) {
        if (porBloco.isEmpty()) {
            return operacao;
        }
        var blocos = new EnumMap<Bloco, Object>(Bloco.class);
        operacao.blocos().forEach((bloco, conteudo) -> {
            var no = porBloco.get(bloco);
            blocos.put(bloco, no == null ? conteudo : no.projetar(conteudo));
        });
        return new OperacaoConsultada(blocos);
    }

    private static SelecaoDeCamposInvalidaException blocoDesconhecido(String nome) {
        var validos = Arrays.stream(Bloco.values()).map(Bloco::nome).collect(Collectors.joining(", "));
        return new SelecaoDeCamposInvalidaException(
                "Bloco '" + nome + "' inválido no parâmetro fields. Blocos válidos: " + validos);
    }

    private static SelecaoDeCamposInvalidaException campoDesconhecido(String caminho, DefinicaoBloco definicao) {
        var validos = caminhosValidos(definicao.bloco().nome(), definicao.campos()).collect(Collectors.joining(", "));
        return new SelecaoDeCamposInvalidaException(
                "Campo '" + caminho + "' inválido no parâmetro fields. Campos válidos: " + validos);
    }

    private static Stream<String> caminhosValidos(String prefixo, List<Campo> campos) {
        return campos.stream().flatMap(campo -> {
            var caminho = prefixo + "." + campo.nome();
            return Stream.concat(
                    Stream.of(caminho), caminhosValidos(caminho, campo.campos()));
        });
    }

    /** Nó da árvore de campos selecionados de um bloco. Nó {@code inteiro} mantém o valor completo. */
    private static final class No {

        private final Map<String, No> filhos = new LinkedHashMap<>();
        private boolean inteiro;

        void adicionar(String[] partes, int indice) {
            if (inteiro) {
                return;
            }
            if (indice == partes.length) {
                inteiro = true;
                filhos.clear();
                return;
            }
            filhos.computeIfAbsent(partes[indice], nome -> new No()).adicionar(partes, indice + 1);
        }

        Object projetar(Object valor) {
            return switch (valor) {
                case null -> null;
                case Object v when inteiro -> v;
                case Map<?, ?> mapa -> projetarObjeto(mapa);
                case List<?> lista -> lista.stream().map(this::projetar).toList();
                default -> valor;
            };
        }

        private Map<String, Object> projetarObjeto(Map<?, ?> mapa) {
            var projetado = new LinkedHashMap<String, Object>();
            mapa.forEach((chave, valor) -> {
                var nome = String.valueOf(chave);
                var filho = filhos.get(nome);
                if (filho != null) {
                    projetado.put(nome, filho.projetar(valor));
                } else if (DefinicaoBloco.ID_OPERACAO.equals(nome)) {
                    projetado.put(nome, valor);
                }
            });
            return projetado;
        }
    }
}
