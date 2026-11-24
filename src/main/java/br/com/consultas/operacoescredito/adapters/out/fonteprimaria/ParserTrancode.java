package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parser genérico de trancode: lê qualquer bloco a partir do seu {@link LayoutTrancode} e produz o mesmo
 * conteúdo que a visão consolidada desserializa do JSON ({@code Map} para bloco-objeto, {@code List} de
 * {@code Map} para bloco-lista), com as chaves na ordem do layout. Campo em branco vira {@code null}; campo marcado
 * {@code ignorar} não aparece. Os layouts paginados são lidos uma página por vez ({@link #lerPagina}).
 */
final class ParserTrancode {

    /** Lê um bloco-objeto. */
    Map<String, Object> lerObjeto(String trancode, LayoutTrancode layout) {
        if (layout.paginado()) {
            throw new IllegalArgumentException("Layout " + layout.bloco() + " é paginado: use lerPagina");
        }
        var cursor = new Cursor(trancode);
        var conteudo = lerRegistro(cursor, layout.registro());
        exigirFim(cursor, layout);
        return conteudo;
    }

    /** Lê uma página de um layout paginado: as ocorrências e, se houver mais páginas, a chave da próxima. */
    Pagina lerPagina(String trancode, LayoutTrancode layout) {
        if (!layout.paginado()) {
            throw new IllegalArgumentException("Layout " + layout.bloco() + " não é paginado: use lerObjeto");
        }
        var cursor = new Cursor(trancode);
        var quantidade = quantidade(cursor, layout.bloco());
        if (quantidade > LayoutTrancode.MAXIMO_POR_PAGINA) {
            throw new TrancodeInvalidoException("Página de " + layout.bloco() + " com " + quantidade
                    + " ocorrências; o máximo é " + LayoutTrancode.MAXIMO_POR_PAGINA);
        }
        var indicador = cursor.avancar(1);
        var chave = cursor.avancar(LayoutTrancode.TAMANHO_CHAVE_CONTINUACAO).strip();
        Optional<String> proxima = switch (indicador) {
            case "S" -> {
                if (chave.isEmpty()) {
                    throw new TrancodeInvalidoException(
                            "Página de " + layout.bloco() + " indica mais páginas sem chave de continuação");
                }
                yield Optional.of(chave);
            }
            case "N" -> Optional.empty();
            default -> throw new TrancodeInvalidoException(
                    "Indicador de continuação de " + layout.bloco() + " inválido: '" + indicador + "'");
        };
        var itens = new ArrayList<Map<String, Object>>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            itens.add(lerRegistro(cursor, layout.registro()));
        }
        exigirFim(cursor, layout);
        return new Pagina(itens, proxima);
    }

    private static void exigirFim(Cursor cursor, LayoutTrancode layout) {
        if (cursor.restante() > 0) {
            throw new TrancodeInvalidoException("Trancode do bloco " + layout.bloco() + " tem "
                    + cursor.restante() + " posições além do layout");
        }
    }

    private Map<String, Object> lerRegistro(Cursor cursor, LayoutTrancode.Registro registro) {
        var texto = cursor.avancar(registro.tamanho());
        var conteudo = new LinkedHashMap<String, Object>();
        registro.campos().stream()
                .filter(campo -> !campo.ignorar())
                .forEach(campo -> conteudo.put(campo.campo(), campo.converter(texto)));
        registro.listas().forEach(lista ->
                conteudo.put(lista.campo(), lerSubLista(cursor, lista.registro(), lista.campo())));
        return conteudo;
    }

    private List<Map<String, Object>> lerSubLista(Cursor cursor, LayoutTrancode.Registro registro, String nome) {
        var quantidade = quantidade(cursor, nome);
        var itens = new ArrayList<Map<String, Object>>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            itens.add(lerRegistro(cursor, registro));
        }
        return itens;
    }

    private static int quantidade(Cursor cursor, String nome) {
        var prefixo = cursor.avancar(LayoutTrancode.TAMANHO_QUANTIDADE);
        if (!prefixo.chars().allMatch(Character::isDigit)) {
            throw new TrancodeInvalidoException("Quantidade de itens de " + nome + " inválida: '" + prefixo + "'");
        }
        return Integer.parseInt(prefixo);
    }

    /**
     * Uma página de ocorrências.
     *
     * @param proxima chave de continuação da próxima página; vazia na última
     */
    record Pagina(List<Map<String, Object>> itens, Optional<String> proxima) {
    }

    /** Posição de leitura no trancode; registros e sub-listas são lidos em sequência. */
    private static final class Cursor {

        private final String trancode;
        private int posicao;

        Cursor(String trancode) {
            this.trancode = trancode;
        }

        String avancar(int tamanho) {
            if (posicao + tamanho > trancode.length()) {
                throw new TrancodeInvalidoException("Trancode termina na posição " + trancode.length()
                        + "; o layout espera " + tamanho + " posições a partir da " + (posicao + 1));
            }
            var trecho = trancode.substring(posicao, posicao + tamanho);
            posicao += tamanho;
            return trecho;
        }

        int restante() {
            return trancode.length() - posicao;
        }
    }
}
