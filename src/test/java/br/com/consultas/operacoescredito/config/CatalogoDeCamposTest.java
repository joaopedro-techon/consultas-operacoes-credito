package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoInvalidoException;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import br.com.consultas.operacoescredito.domain.catalogo.TipoCampo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O catálogo de campos ainda não é observável pela API HTTP (só {@code fields}/{@code filter} o usarão),
 * então é exercitado aqui: o arquivo real {@code catalogo-campos.yml} e o formato YAML com erros.
 */
class CatalogoDeCamposTest {

    @Test
    void catalogoRealDeclaraTodosOsBlocosComAEstruturaDaSpec() throws IOException {
        var catalogo = carregar(new ClassPathResource("catalogo-campos.yml"));

        assertThat(catalogo.definicoes()).containsOnlyKeys(Bloco.values());
        assertThat(catalogo.definicao(Bloco.OPERACAO).estrutura()).isEqualTo(EstruturaBloco.OBJETO);
        assertThat(catalogo.definicao(Bloco.PARCELAS).estrutura()).isEqualTo(EstruturaBloco.LISTA);
        assertThat(catalogo.definicao(Bloco.PLANO).estrutura()).isEqualTo(EstruturaBloco.OBJETO);
        assertThat(catalogo.definicao(Bloco.CONTROLES_IOF).estrutura()).isEqualTo(EstruturaBloco.LISTA);
    }

    @ParameterizedTest(name = "{0}.{1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            operacao      | id_operacao                                          | NUMERO
            operacao      | data_contratacao_operacao                            | DATA
            operacao      | cpf_cnpj                                             | TEXTO
            parcelas      | numero_parcela_amortizacao                           | NUMERO
            parcelas      | valor_principal_parcela                              | DECIMAL
            amortizacoes  | indicador_ocorrencia_valida                          | BOOLEANO
            amortizacoes  | data_valor_movimento                                 | DATA_HORA
            plano         | dados_plano_amortizacao                              | LISTA
            plano         | dados_plano_amortizacao.taxa_juros_efetiva           | DECIMAL
            plano         | dados_plano_amortizacao.encargos_moratorios.dia_fim  | NUMERO
            plano         | dados_plano_amortizacao.desconto_bom_pagador.percentual_desconto | DECIMAL
            saldo         | saldo_parcelas.saldo_devedor                         | DECIMAL
            controles_iof | numero_sequencial_controle_iof                       | NUMERO
            controles_iof | valor_iof_pago                                       | DECIMAL
            controles_iof | data_pagamento_iof                                   | DATA
            """)
    void catalogoRealTipaCadaCampoPeloCaminho(String bloco, String caminho, TipoCampo tipo) throws IOException {
        var catalogo = carregar(new ClassPathResource("catalogo-campos.yml"));

        assertThat(catalogo.campo(Bloco.porNome(bloco).orElseThrow(), caminho))
                .hasValueSatisfying(campo -> assertThat(campo.tipo()).isEqualTo(tipo));
    }

    @Test
    void camposQuePodemVirNulosSaoAnulaveis() throws IOException {
        var catalogo = carregar(new ClassPathResource("catalogo-campos.yml"));

        assertThat(catalogo.campo(Bloco.OPERACAO, "data_baixa_operacao")).hasValueSatisfying(
                campo -> assertThat(campo.anulavel()).isTrue());
        assertThat(catalogo.campo(Bloco.CONTROLES_IOF, "data_pagamento_iof")).hasValueSatisfying(
                campo -> assertThat(campo.anulavel()).isTrue());
        assertThat(catalogo.campo(Bloco.OPERACAO, "data_contratacao_operacao")).hasValueSatisfying(
                campo -> assertThat(campo.anulavel()).isFalse());
    }

    @ParameterizedTest(name = "{0}.{1} desconhecido")
    @CsvSource(delimiter = '|', textBlock = """
            parcelas | numero_parcela
            parcelas | situacao_parcela.codigo
            plano    | taxa_juros_nominal
            saldo    | saldo_parcelas.inexistente
            saldo    | ''
            """)
    void campoForaDoCatalogoNaoEhEncontrado(String bloco, String caminho) throws IOException {
        var catalogo = carregar(new ClassPathResource("catalogo-campos.yml"));

        assertThat(catalogo.campo(Bloco.porNome(bloco).orElseThrow(), caminho)).isEmpty();
    }

    @Test
    void blocoSemDefinicaoNoCatalogoImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: operacao
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: numero }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("originacao")
                .hasMessageContaining("controles_iof");
    }

    @Test
    void blocoDesconhecidoNoCatalogoImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: garantias
                  estrutura: lista
                  campos:
                    - { nome: id_operacao, tipo: numero }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("garantias")
                .hasMessageContaining("controles_iof");
    }

    @Test
    void blocoDefinidoDuasVezesImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: operacao
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: numero }
                - bloco: operacao
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: numero }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("operacao")
                .hasMessageContaining("mais de uma vez");
    }

    @Test
    void tipoDesconhecidoImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: operacao
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: inteiro_longo }
                """)))
                .isInstanceOf(BindException.class);
    }

    @Test
    void blocoSemIdOperacaoNumericoImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: operacao
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: texto }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("id_operacao");
    }

    @Test
    void campoCompostoSemSubcamposImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: saldo
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: numero }
                    - { nome: saldo_parcelas, tipo: lista }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("saldo_parcelas");
    }

    @Test
    void campoSimplesComSubcamposImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: saldo
                  estrutura: objeto
                  campos:
                    - { nome: id_operacao, tipo: numero }
                    - nome: saldo_devedor_operacao
                      tipo: decimal
                      campos:
                        - { nome: centavos, tipo: numero }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("saldo_devedor_operacao");
    }

    @Test
    void campoRepetidoImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: parcelas
                  estrutura: lista
                  campos:
                    - { nome: id_operacao, tipo: numero }
                    - { nome: situacao_parcela, tipo: texto }
                    - { nome: situacao_parcela, tipo: texto }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("situacao_parcela");
    }

    @Test
    void blocoSemEstruturaImpedeASubida() {
        assertThatThrownBy(() -> carregar(yaml("""
                - bloco: parcelas
                  campos:
                    - { nome: id_operacao, tipo: numero }
                """)))
                .isInstanceOf(CatalogoInvalidoException.class)
                .hasMessageContaining("parcelas");
    }

    private static CatalogoDeCampos carregar(Resource recurso) throws IOException {
        var fontes = new YamlPropertySourceLoader().load("catalogo", recurso);
        var binder = new Binder(ConfigurationPropertySources.from(fontes));
        return binder.bind("consultas.catalogo", CatalogoDeCamposProperties.class)
                .orElseThrow(() -> new IllegalStateException("consultas.catalogo ausente"))
                .paraCatalogo();
    }

    private static Resource yaml(String blocos) {
        var indentado = blocos.indent(6);
        var conteudo = "consultas:\n  catalogo:\n    blocos:\n" + indentado;
        return new ByteArrayResource(conteudo.getBytes(StandardCharsets.UTF_8));
    }
}
