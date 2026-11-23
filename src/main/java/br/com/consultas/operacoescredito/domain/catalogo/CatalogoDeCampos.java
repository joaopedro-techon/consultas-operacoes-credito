package br.com.consultas.operacoescredito.domain.catalogo;

import br.com.consultas.operacoescredito.domain.Bloco;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Catálogo de campos (ADR-0005): os campos válidos e o tipo de cada um, por bloco. É a referência
 * para validar {@code fields}/{@code filter} e tipar as comparações do filtro; os blocos continuam
 * trafegando como {@code Map}. Exige exatamente uma definição para cada {@link Bloco}.
 */
public record CatalogoDeCampos(Map<Bloco, DefinicaoBloco> definicoes) {

    public CatalogoDeCampos {
        var copia = new EnumMap<Bloco, DefinicaoBloco>(Bloco.class);
        copia.putAll(definicoes);
        var faltando = Arrays.stream(Bloco.values()).filter(bloco -> !copia.containsKey(bloco)).toList();
        if (!faltando.isEmpty()) {
            throw new CatalogoInvalidoException("Catálogo de campos sem definição para os blocos: "
                    + faltando.stream().map(Bloco::nome).collect(Collectors.joining(", ")));
        }
        copia.forEach((bloco, definicao) -> {
            if (definicao.bloco() != bloco) {
                throw new CatalogoInvalidoException("Definição do bloco '" + definicao.bloco().nome()
                        + "' registrada como '" + bloco.nome() + "'");
            }
        });
        definicoes = Collections.unmodifiableMap(copia);
    }

    /** Monta o catálogo a partir das definições, rejeitando bloco definido duas vezes. */
    public static CatalogoDeCampos de(Collection<DefinicaoBloco> definicoes) {
        var porBloco = new EnumMap<Bloco, DefinicaoBloco>(Bloco.class);
        for (var definicao : definicoes) {
            if (porBloco.putIfAbsent(definicao.bloco(), definicao) != null) {
                throw new CatalogoInvalidoException(
                        "Bloco '" + definicao.bloco().nome() + "' definido mais de uma vez no catálogo de campos");
            }
        }
        return new CatalogoDeCampos(porBloco);
    }

    public DefinicaoBloco definicao(Bloco bloco) {
        return definicoes.get(bloco);
    }

    /** Campo de um bloco pelo caminho (ver {@link DefinicaoBloco#campo(String)}). */
    public Optional<Campo> campo(Bloco bloco, String caminho) {
        return definicao(bloco).campo(caminho);
    }
}
