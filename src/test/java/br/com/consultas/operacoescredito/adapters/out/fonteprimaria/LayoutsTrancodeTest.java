package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.domain.Bloco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validação dos layouts de trancode na subida. Um layout inconsistente impede a aplicação de iniciar,
 * caso impraticável de exercitar pela API HTTP.
 */
class LayoutsTrancodeTest {

    private static final String ORIGEM = "src/main/resources/trancode/layouts";

    @TempDir
    Path diretorio;

    @Test
    void layoutsDoProjetoCarregamUmPorBloco() {
        var layouts = LayoutsTrancode.carregar(new DefaultResourceLoader(), "classpath:trancode/layouts/");

        for (var bloco : Bloco.values()) {
            assertThat(layouts.de(bloco).bloco()).isEqualTo(bloco.nome());
        }
    }

    @Test
    void layoutAusenteImpedeACarga() throws IOException {
        copiarLayoutsExceto("saldo");

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("saldo.yml");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            campos sobrepostos   | { campo: canal_contratacao, inicio: 18, tamanho: 20, tipo: texto }                  | sobrepõe
            inicio zero          | { campo: canal_contratacao, inicio: 0, tamanho: 20, tipo: texto }                   | positivos
            decimal sem casas    | { campo: canal_contratacao, inicio: 19, tamanho: 20, tipo: decimal }                | formato inválido
            data com formato ruim| { campo: canal_contratacao, inicio: 19, tamanho: 10, tipo: data, formato: 'yyyy-bb' } | formato inválido
            booleano com 3 letras| { campo: canal_contratacao, inicio: 19, tamanho: 1, tipo: booleano, formato: SNX }  | formato inválido
            sem tipo             | { campo: canal_contratacao, inicio: 19, tamanho: 20 }                               | sem tipo
            """)
    void layoutInconsistenteImpedeACarga(String caso, String campo, String motivo) throws IOException {
        copiarLayoutsExceto("originacao");
        Files.writeString(diretorio.resolve("originacao.yml"), """
                bloco: originacao
                estrutura: objeto
                registro:
                  campos:
                    - { campo: id_operacao, inicio: 1, tamanho: 18, tipo: numero }
                    - %s
                """.formatted(campo));

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("originacao")
                .hasMessageContaining(motivo);
    }

    @Test
    void layoutQueDeclaraOutroBlocoImpedeACarga() throws IOException {
        copiarLayoutsExceto("parcelas");
        Files.copy(Path.of(ORIGEM, "componentes.yml"), diretorio.resolve("parcelas.yml"));

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("declara bloco 'componentes'");
    }

    private LayoutsTrancode carregar() {
        return LayoutsTrancode.carregar(new DefaultResourceLoader(), diretorio.toUri().toString());
    }

    private void copiarLayoutsExceto(String excluido) throws IOException {
        for (var bloco : Bloco.values()) {
            if (!bloco.nome().equals(excluido)) {
                Files.copy(Path.of(ORIGEM, bloco.nome() + ".yml"), diretorio.resolve(bloco.nome() + ".yml"));
            }
        }
    }
}
