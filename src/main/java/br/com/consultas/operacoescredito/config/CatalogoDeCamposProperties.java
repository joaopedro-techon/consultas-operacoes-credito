package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.catalogo.Campo;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoInvalidoException;
import br.com.consultas.operacoescredito.domain.catalogo.DefinicaoBloco;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import br.com.consultas.operacoescredito.domain.catalogo.TipoCampo;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Catálogo de campos como declarado em {@code catalogo-campos.yml} (ADR-0005).
 *
 * @param blocos uma entrada por bloco
 */
@ConfigurationProperties("consultas.catalogo")
public record CatalogoDeCamposProperties(List<BlocoProperties> blocos) {

    /**
     * @param bloco     nome canônico do bloco
     * @param estrutura objeto ou lista
     * @param campos    campos do bloco
     */
    public record BlocoProperties(String bloco, EstruturaBloco estrutura, List<CampoProperties> campos) {
    }

    /**
     * @param nome     nome do campo
     * @param tipo     tipo do campo
     * @param anulavel aceita {@code null}; ausente equivale a {@code false}
     * @param campos   subcampos de campos {@code objeto}/{@code lista}
     */
    public record CampoProperties(String nome, TipoCampo tipo, Boolean anulavel, List<CampoProperties> campos) {
    }

    /** Converte para o catálogo do domínio, que valida a consistência. */
    CatalogoDeCampos paraCatalogo() {
        if (blocos == null || blocos.isEmpty()) {
            throw new CatalogoInvalidoException("Catálogo de campos vazio (consultas.catalogo.blocos)");
        }
        return CatalogoDeCampos.de(blocos.stream().map(CatalogoDeCamposProperties::definicao).toList());
    }

    private static DefinicaoBloco definicao(BlocoProperties propriedades) {
        var bloco = Bloco.porNome(propriedades.bloco()).orElseThrow(() -> new CatalogoInvalidoException(
                "Bloco '" + propriedades.bloco() + "' desconhecido no catálogo de campos. Blocos válidos: "
                        + Arrays.stream(Bloco.values()).map(Bloco::nome).collect(Collectors.joining(", "))));
        return new DefinicaoBloco(bloco, propriedades.estrutura(), campos(propriedades.campos()));
    }

    private static List<Campo> campos(List<CampoProperties> campos) {
        if (campos == null) {
            return List.of();
        }
        return campos.stream()
                .map(campo -> new Campo(campo.nome(), campo.tipo(), Boolean.TRUE.equals(campo.anulavel()),
                        campos(campo.campos())))
                .toList();
    }
}
