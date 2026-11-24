package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser genérico de trancode: lê qualquer bloco a partir do seu {@link LayoutTrancode} e produz o mesmo
 * conteúdo que a visão consolidada desserializa do JSON ({@code Map} para bloco-objeto, {@code List} de
 * {@code Map} para bloco-lista), com as chaves na ordem do layout. Campo em branco vira {@code null}.
 */
final class ParserTrancode {

    Object ler(String trancode, LayoutTrancode layout) {
        var cursor = new Cursor(trancode);
        Object conteudo = switch (layout.estrutura()) {
            case OBJETO -> lerRegistro(cursor, layout.registro());
            case LISTA -> lerLista(cursor, layout.registro(), layout.bloco());
        };
        if (cursor.restante() > 0) {
            throw new TrancodeInvalidoException("Trancode do bloco " + layout.bloco() + " tem "
                    + cursor.restante() + " posições além do layout");
        }
        return conteudo;
    }

    private Map<String, Object> lerRegistro(Cursor cursor, LayoutTrancode.Registro registro) {
        var texto = cursor.avancar(registro.tamanho());
        var conteudo = new LinkedHashMap<String, Object>();
        registro.campos().forEach(campo -> conteudo.put(campo.campo(), campo.converter(texto)));
        registro.listas().forEach(lista ->
                conteudo.put(lista.campo(), lerLista(cursor, lista.registro(), lista.campo())));
        return conteudo;
    }

    private List<Map<String, Object>> lerLista(Cursor cursor, LayoutTrancode.Registro registro, String nome) {
        var prefixo = cursor.avancar(LayoutTrancode.TAMANHO_QUANTIDADE);
        if (!prefixo.chars().allMatch(Character::isDigit)) {
            throw new TrancodeInvalidoException("Quantidade de itens de " + nome + " inválida: '" + prefixo + "'");
        }
        var quantidade = Integer.parseInt(prefixo);
        var itens = new ArrayList<Map<String, Object>>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            itens.add(lerRegistro(cursor, registro));
        }
        return itens;
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
