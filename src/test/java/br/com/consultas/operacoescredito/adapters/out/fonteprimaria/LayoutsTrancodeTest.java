package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;
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

        for (var custodia : Custodia.values()) {
            for (var bloco : Bloco.values()) {
                assertThat(layouts.de(custodia, bloco).bloco()).isEqualTo(bloco.nome());
            }
        }
    }

    @Test
    void overrideDaCustodiaComCamposEmOutrasPosicoesDevolveAsChavesNaOrdemDoBase() {
        var layouts = LayoutsTrancode.carregar(new DefaultResourceLoader(), "classpath:trancode/layouts/");
        var base = layouts.de(Custodia.SF, Bloco.PARCELAS).registro().campos();
        var nsf = layouts.de(Custodia.NSF, Bloco.PARCELAS).registro().campos();

        assertThat(nsf).extracting(LayoutTrancode.Campo::campo)
                .containsExactlyElementsOf(base.stream().map(LayoutTrancode.Campo::campo).toList());
        assertThat(nsf).extracting(LayoutTrancode.Campo::inicio)
                .as("as posições são as do override").isNotEqualTo(base.stream().map(LayoutTrancode.Campo::inicio).toList());
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

    @Test
    void layoutDaListaDeOperacoesTemIdEDataDeContratacao() {
        var lista = LayoutsTrancode.carregar(new DefaultResourceLoader(), "classpath:trancode/layouts/")
                .listaOperacoes(Custodia.SF);

        assertThat(lista.estrutura()).isEqualTo(LayoutTrancode.Estrutura.LISTA);
        assertThat(lista.registro().campos()).extracting(LayoutTrancode.Campo::campo)
                .containsExactly("id_operacao", "data_contratacao_operacao");
    }

    @Test
    void layoutDaListaDeOperacoesAusenteImpedeACarga() throws IOException {
        copiarLayoutsExceto(LayoutsTrancode.LISTA_OPERACOES);

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("lista_operacoes.yml");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            objeto em vez de lista | objeto | { campo: id_operacao, inicio: 1, tamanho: 18, tipo: numero }              | estrutura lista
            id como texto          | lista  | { campo: id_operacao, inicio: 1, tamanho: 18, tipo: texto }               | id_operacao do tipo numero
            sem id                 | lista  | { campo: numero, inicio: 1, tamanho: 18, tipo: numero }                   | id_operacao do tipo numero
            """)
    void layoutDaListaDeOperacoesInconsistenteImpedeACarga(String caso, String estrutura, String campoId, String motivo)
            throws IOException {
        copiarLayoutsExceto(LayoutsTrancode.LISTA_OPERACOES);
        Files.writeString(diretorio.resolve("lista_operacoes.yml"), """
                bloco: lista_operacoes
                estrutura: %s
                registro:
                  campos:
                    - %s
                    - { campo: data_contratacao_operacao, inicio: 19, tamanho: 10, tipo: data, formato: yyyy-MM-dd }
                """.formatted(estrutura, campoId));

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("lista_operacoes")
                .hasMessageContaining(motivo);
    }

    @Test
    void layoutDaListaDeOperacoesSemDataDeContratacaoImpedeACarga() throws IOException {
        copiarLayoutsExceto(LayoutsTrancode.LISTA_OPERACOES);
        Files.writeString(diretorio.resolve("lista_operacoes.yml"), """
                bloco: lista_operacoes
                estrutura: lista
                registro:
                  campos:
                    - { campo: id_operacao, inicio: 1, tamanho: 18, tipo: numero }
                """);

        assertThatThrownBy(this::carregar)
                .isInstanceOf(LayoutsTrancode.LayoutTrancodeInvalidoException.class)
                .hasMessageContaining("data_contratacao_operacao do tipo data");
    }

    private LayoutsTrancode carregar() {
        return LayoutsTrancode.carregar(new DefaultResourceLoader(), diretorio.toUri().toString());
    }

    private void copiarLayoutsExceto(String excluido) throws IOException {
        var nomes = new java.util.ArrayList<String>();
        for (var bloco : Bloco.values()) {
            nomes.add(bloco.nome());
        }
        nomes.add(LayoutsTrancode.LISTA_OPERACOES);
        for (var nome : nomes) {
            if (!nome.equals(excluido)) {
                Files.copy(Path.of(ORIGEM, nome + ".yml"), diretorio.resolve(nome + ".yml"));
            }
        }
    }
}
