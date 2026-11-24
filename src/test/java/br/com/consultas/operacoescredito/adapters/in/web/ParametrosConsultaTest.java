package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParametrosConsultaTest {

    @Test
    void operacaoClienteEContaViramOCriterio() {
        assertThat(parametros("OPERACAO", "100000001", null, null, null).criterio())
                .isEqualTo(new CriterioConsulta.PorOperacao(100000001L));
        assertThat(parametros("CLIENTE", null, "12ABC34501DE35", null, "10").criterio())
                .isEqualTo(new CriterioConsulta.PorCliente("12ABC34501DE35", OptionalInt.of(10)));
        assertThat(parametros("CONTA", null, null, "434115000065874", null).criterio())
                .isEqualTo(new CriterioConsulta.PorConta("434115000065874", OptionalInt.empty()));
    }

    @Test
    void contaComVinteDigitosEAceita() {
        var conta = "1".repeat(20);

        assertThat(parametros("CONTA", null, null, conta, null).criterio())
                .isEqualTo(new CriterioConsulta.PorConta(conta, OptionalInt.empty()));
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 3000})
    void contaLongaDemaisE400(int digitos) {
        assertThatThrownBy(() -> parametros("CONTA", null, null, "1".repeat(digitos), null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessage("Parâmetro conta deve ter só dígitos, de 1 a 20 dígitos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12a", "-1", "4341.15"})
    void contaComCaractereNaoNumericoE400(String conta) {
        assertThatThrownBy(() -> parametros("CONTA", null, null, conta, null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1234567890123456789", "12a", "-5"})
    void idOperacaoForaDoFormatoE400(String id) {
        assertThatThrownBy(() -> parametros("OPERACAO", id, null, null, null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("18 dígitos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"529.982.247-25", "52998224724", "12abc34501de35", "5299822472500000"})
    void cpfCnpjInvalidoE400(String documento) {
        assertThatThrownBy(() -> parametros("CLIENTE", null, documento, null, null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "501", "abc", "99999"})
    void limiteForaDe1a500E400(String limite) {
        assertThatThrownBy(() -> parametros("CLIENTE", null, "52998224725", null, limite).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("1 a 500");
    }

    @Test
    void tipoDeConsultaInvalidoNaoEcoaOValorRecebido() {
        assertThatThrownBy(() -> parametros("<script>52998224725", "1", null, null, null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessage("Header x-tipo-consulta inválido. Valores válidos: OPERACAO, CLIENTE, CONTA")
                .message().doesNotContain("52998224725");
    }

    @Test
    void tipoDeConsultaAusenteE400() {
        assertThatThrownBy(() -> parametros(null, "1", null, null, null).criterio())
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("obrigatório");
    }

    @Test
    void parametroIncoerenteComOTipoE400() {
        assertThatThrownBy(() -> parametros("OPERACAO", "1", "52998224725", null, null).criterio())
                .hasMessageContaining("cpf_cnpj não se aplica");
        assertThatThrownBy(() -> parametros("OPERACAO", "1", null, null, "10").criterio())
                .hasMessageContaining("limite_operacoes não se aplica");
        assertThatThrownBy(() -> parametros("CONTA", null, null, null, null).criterio())
                .hasMessageContaining("conta é obrigatório");
    }

    @Test
    void expandEFieldsViramBlocosECampos() {
        var parametros = new ParametrosConsulta("OPERACAO", "1", null, null, null, " parcelas ,saldo,parcelas",
                "operacao.id_operacao , saldo.saldo_parcelas");

        assertThat(parametros.blocos()).containsExactly(Bloco.PARCELAS, Bloco.SALDO);
        assertThat(parametros.campos()).containsExactly("operacao.id_operacao", "saldo.saldo_parcelas");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARCELAS", "parcelas,", "xpto"})
    void blocoDesconhecidoNoExpandE400(String expand) {
        var parametros = new ParametrosConsulta("OPERACAO", "1", null, null, null, expand, null);

        assertThatThrownBy(parametros::blocos)
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessageContaining("Blocos válidos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id_operacao", "cpf_cnpj", "conta", "limite_operacoes", "expand", "fields", "filter"})
    void parametroDoContratoRepetidoE400(String parametro) {
        var repetido = Map.of(parametro, new String[] {"a", "b"});

        assertThatThrownBy(() -> ParametrosConsulta.rejeitarRepetidos(repetido))
                .isInstanceOf(RequisicaoInvalidaException.class)
                .hasMessage("Parâmetro " + parametro + " informado mais de uma vez; informe-o uma única vez "
                        + "(listas vão separadas por vírgula no mesmo parâmetro)");
    }

    @Test
    void parametrosUnicosOuForaDoContratoNaoSaoRejeitados() {
        var parametros = Map.of(
                "id_operacao", new String[] {"1"},
                "expand", new String[] {"parcelas,saldo"},
                "outro", new String[] {"x", "y"});

        assertThatCode(() -> ParametrosConsulta.rejeitarRepetidos(parametros)).doesNotThrowAnyException();
    }

    private static ParametrosConsulta parametros(String tipo, String idOperacao, String cpfCnpj, String conta,
                                                 String limite) {
        return new ParametrosConsulta(tipo, idOperacao, cpfCnpj, conta, limite, null, null);
    }
}
