package br.com.consultas.operacoescredito.domain.catalogo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Campo de um bloco no catálogo de campos. Campos {@link TipoCampo#composto() compostos}
 * ({@code OBJETO}/{@code LISTA}) declaram seus subcampos; os demais não têm subcampos.
 *
 * @param nome     nome do campo no JSON do bloco
 * @param tipo     tipo do valor
 * @param anulavel {@code true} quando a origem pode mandar {@code null}
 * @param campos   subcampos, só para campos compostos
 */
public record Campo(String nome, TipoCampo tipo, boolean anulavel, List<Campo> campos) {

    public Campo {
        if (nome == null || nome.isBlank()) {
            throw new CatalogoInvalidoException("Campo sem nome");
        }
        if (tipo == null) {
            throw new CatalogoInvalidoException("Campo '" + nome + "' sem tipo");
        }
        campos = campos == null ? List.of() : List.copyOf(campos);
        if (tipo.composto() && campos.isEmpty()) {
            throw new CatalogoInvalidoException("Campo '" + nome + "' do tipo " + tipo + " precisa declarar subcampos");
        }
        if (!tipo.composto() && !campos.isEmpty()) {
            throw new CatalogoInvalidoException("Campo '" + nome + "' do tipo " + tipo + " não pode ter subcampos");
        }
        Campos.exigirNomesUnicos(campos, "campo '" + nome + "'");
    }

    /** Campo simples (não anulável, sem subcampos). */
    public static Campo de(String nome, TipoCampo tipo) {
        return new Campo(nome, tipo, false, List.of());
    }

    /** Subcampo direto deste campo composto. */
    public Optional<Campo> subcampo(String nome) {
        return Campos.porNome(campos, nome);
    }

    /** Utilitários internos do pacote para listas de campos. */
    static final class Campos {

        private Campos() {
        }

        static Optional<Campo> porNome(List<Campo> campos, String nome) {
            return campos.stream().filter(campo -> campo.nome().equals(nome)).findFirst();
        }

        /** Resolve um caminho com ponto ({@code a.b.c}) descendo por campos compostos. */
        static Optional<Campo> porCaminho(List<Campo> campos, String caminho) {
            Objects.requireNonNull(caminho, "caminho");
            var partes = caminho.split("\\.", -1);
            var atual = porNome(campos, partes[0]);
            for (int i = 1; i < partes.length && atual.isPresent(); i++) {
                atual = atual.get().subcampo(partes[i]);
            }
            return atual;
        }

        static void exigirNomesUnicos(List<Campo> campos, String onde) {
            var nomes = new HashSet<String>();
            for (var campo : campos) {
                if (!nomes.add(campo.nome())) {
                    throw new CatalogoInvalidoException("Campo '" + campo.nome() + "' repetido em " + onde);
                }
            }
        }
    }
}
