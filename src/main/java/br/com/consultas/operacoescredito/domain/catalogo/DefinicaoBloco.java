package br.com.consultas.operacoescredito.domain.catalogo;

import br.com.consultas.operacoescredito.domain.Bloco;

import java.util.List;
import java.util.Optional;

/**
 * Campos e tipos de um bloco no catálogo de campos. Todo bloco declara {@code id_operacao} numérico
 * no nível de cima, porque ele vem sempre em todo item.
 *
 * @param bloco      bloco descrito
 * @param estrutura  objeto ou lista de objetos
 * @param campos     campos do objeto (ou de cada item da lista)
 */
public record DefinicaoBloco(Bloco bloco, EstruturaBloco estrutura, List<Campo> campos) {

    public static final String ID_OPERACAO = "id_operacao";

    public DefinicaoBloco {
        if (bloco == null) {
            throw new CatalogoInvalidoException("Definição de bloco sem bloco");
        }
        if (estrutura == null) {
            throw new CatalogoInvalidoException("Bloco '" + bloco.nome() + "' sem estrutura (objeto ou lista)");
        }
        campos = campos == null ? List.of() : List.copyOf(campos);
        Campo.Campos.exigirNomesUnicos(campos, "bloco '" + bloco.nome() + "'");
        var idOperacao = Campo.Campos.porNome(campos, ID_OPERACAO);
        if (idOperacao.isEmpty() || idOperacao.get().tipo() != TipoCampo.NUMERO) {
            throw new CatalogoInvalidoException(
                    "Bloco '" + bloco.nome() + "' precisa declarar " + ID_OPERACAO + " do tipo " + TipoCampo.NUMERO);
        }
    }

    /**
     * Campo pelo caminho, com ponto para campos aninhados
     * (ex.: {@code saldo_parcelas.saldo_devedor} no bloco {@code saldo}).
     */
    public Optional<Campo> campo(String caminho) {
        return Campo.Campos.porCaminho(campos, caminho);
    }
}
