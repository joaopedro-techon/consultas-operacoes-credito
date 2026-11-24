package br.com.consultas.operacoescredito.config.observabilidade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mascaramento de texto livre: casos que a API ainda não consegue levar a um log (mensagens de
 * exceção, documentos soltos), por isso testados isoladamente.
 */
class MascaramentoDadosPessoaisTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Cliente 52998224725 sem operações          | Cliente 529******25 sem operações
            Cliente 529.982.247-25 sem operações       | Cliente 529******25 sem operações
            CNPJ 11222333000181 consultado             | CNPJ 112*********81 consultado
            CNPJ 11.222.333/0001-81 consultado         | CNPJ 112*********81 consultado
            CNPJ 12ABC34501DE35 consultado             | CNPJ 12A*********35 consultado
            {"cpf_cnpj":"11111111111","conta":"98765"} | {"cpf_cnpj":"111******11","conta":"*8765"}
            GET /v1?conta=123456789&expand=saldo       | GET /v1?conta=*****6789&expand=saldo
            filtro operacao.cpf_cnpj=in=52998224725    | filtro operacao.cpf_cnpj=in=529******25
            http://servico/contas/434115000065874/operacoes | http://servico/contas/***********5874/operacoes
            http://servico/clientes/39053344705/operacoes   | http://servico/clientes/390******05/operacoes
            /api/clientes/12ABC34501DE35/operacoes         | /api/clientes/12A*********35/operacoes
            """)
    void mascaraDocumentosEContasNoTexto(String texto, String esperado) {
        assertThat(MascaramentoDadosPessoais.mascarar(texto)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "{0} permanece")
    @CsvSource(delimiter = '|', textBlock = """
            Operação 100000001 não encontrada
            Número 12345678901 com DV inválido
            Duração 52998224725123 ms
            desconta=123456 não é conta
            Requisição sem dígitos
            """)
    void naoMascaraNumerosQueNaoSaoDadosPessoais(String texto) {
        assertThat(MascaramentoDadosPessoais.mascarar(texto)).isEqualTo(texto);
    }

    @Test
    void campoComNomeDeDadoPessoalTemOValorInteiroMascarado() {
        assertThat(MascaramentoDadosPessoais.mascarar("cpf_cnpj", "12345678901")).isEqualTo("123******01");
        assertThat(MascaramentoDadosPessoais.mascarar("conta", "434115000065874")).isEqualTo("***********5874");
        assertThat(MascaramentoDadosPessoais.mascarar("conta", "123")).isEqualTo("***");
    }

    @Test
    void identificadorDeOperacaoNuncaEMascarado() {
        assertThat(MascaramentoDadosPessoais.mascarar("id_operacao", "52998224725")).isEqualTo("52998224725");
    }

    @Test
    void valoresNulosOuVaziosPassamIntactos() {
        assertThat(MascaramentoDadosPessoais.mascarar(null)).isNull();
        assertThat(MascaramentoDadosPessoais.mascarar("cpf_cnpj", "")).isEmpty();
    }
}
