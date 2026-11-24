package br.com.consultas.operacoescredito.application.service;

import br.com.consultas.operacoescredito.application.port.in.Consumo;
import br.com.consultas.operacoescredito.application.port.out.EventoDeConsumo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrarConsumoServiceTest {

    private static final byte[] SEGREDO = "segredo".getBytes(StandardCharsets.UTF_8);
    private static final String CPF = "52998224725";
    private static final String CNPJ = "12ABC34501DE35";
    private static final String CONTA = "434115000065874";

    private final List<EventoDeConsumo> publicados = new ArrayList<>();
    private final RegistrarConsumoService service = new RegistrarConsumoService(publicados::add, SEGREDO);
    private final PseudonimizadorHmac hmac = new PseudonimizadorHmac(SEGREDO);

    @Test
    void hmacSha256EmHexMinusculo() {
        // RFC 4231, caso 2: chave "Jefe"
        var rfc = new PseudonimizadorHmac("Jefe".getBytes(StandardCharsets.UTF_8));

        assertThat(rfc.hmac("what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void mesmoValorDaSempreOMesmoHmacEOutroSegredoDaOutro() {
        var outro = new PseudonimizadorHmac("outro".getBytes(StandardCharsets.UTF_8));

        assertThat(hmac.hmac(CPF)).isEqualTo(hmac.hmac(CPF)).isNotEqualTo(outro.hmac(CPF)).hasSize(64);
    }

    @Test
    void segredoVazioNaoEAceito() {
        assertThatThrownBy(() -> new PseudonimizadorHmac(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventoLevaCpfEContaSoComoHmacEMantemOResto() {
        service.registrar(consumo(CPF, CONTA, "operacao.cpf_cnpj==" + CPF));

        assertThat(publicados).singleElement().satisfies(evento -> {
            assertThat(evento.versaoEvento()).isEqualTo(EventoDeConsumo.VERSAO);
            assertThat(evento.cpfCnpjHmac()).isEqualTo(hmac.hmac(CPF));
            assertThat(evento.contaHmac()).isEqualTo(hmac.hmac(CONTA));
            assertThat(evento.filtro()).isEqualTo("operacao.cpf_cnpj==" + hmac.hmac(CPF));
            assertThat(evento.correlationId()).isEqualTo("c-1");
            assertThat(evento.consumidor()).isEqualTo("sistema");
            assertThat(evento.blocosSolicitados()).containsExactly("operacao", "originacao");
            assertThat(evento.cacheHits()).containsEntry("operacao", 1L);
            assertThat(evento.statusHttp()).isEqualTo(200);
            assertThat(evento.toString()).doesNotContain(CPF).doesNotContain(CONTA);
        });
    }

    @Test
    void semDocumentoNemContaNemFiltroOsCamposFicamVazios() {
        service.registrar(consumo(null, null, null));

        assertThat(publicados).singleElement().satisfies(evento -> {
            assertThat(evento.cpfCnpjHmac()).isNull();
            assertThat(evento.contaHmac()).isNull();
            assertThat(evento.filtro()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            operacao.cpf_cnpj==52998224725                     | operacao.cpf_cnpj=={52998224725}
            operacao.cpf_cnpj!='52998224725'                   | operacao.cpf_cnpj!={52998224725}
            operacao.cpf_cnpj=in=(52998224725,12ABC34501DE35)  | operacao.cpf_cnpj=in=({52998224725},{12ABC34501DE35})
            originacao.conta==434115000065874                  | originacao.conta=={434115000065874}
            a.b.conta=out=("434115000065874")                  | a.b.conta=out=({434115000065874})
            operacao.id_operacao==52998224725                  | operacao.id_operacao=={52998224725}
            parcelas.numero_parcela_amortizacao=ge=4           | parcelas.numero_parcela_amortizacao=ge=4
            originacao.dependencia_contabil==12345             | originacao.dependencia_contabil==12345
            operacao.situacao_operacao_credito==ATIVA;operacao.cpf_cnpj==12ABC34501DE35 | operacao.situacao_operacao_credito==ATIVA;operacao.cpf_cnpj=={12ABC34501DE35}
            """)
    void filtroTrocaCpfCnpjEContaPeloHmac(String filtro, String esperado) {
        assertThat(hmac.filtro(filtro)).isEqualTo(comHmac(esperado));
    }

    @Test
    void cpfComDigitoInvalidoSoltoNoFiltroNaoEConfundidoComDocumento() {
        assertThat(hmac.filtro("operacao.id_operacao==52998224700")).isEqualTo("operacao.id_operacao==52998224700");
    }

    /** Troca cada {@code {valor}} pelo HMAC do valor. */
    private String comHmac(String modelo) {
        var resultado = new StringBuilder();
        var matcher = java.util.regex.Pattern.compile("\\{([^}]*)}").matcher(modelo);
        while (matcher.find()) {
            matcher.appendReplacement(resultado, hmac.hmac(matcher.group(1)));
        }
        return matcher.appendTail(resultado).toString();
    }

    private static Consumo consumo(String cpfCnpj, String conta, String filtro) {
        return new Consumo(Instant.parse("2026-11-23T10:15:30Z"), "c-1", null, "sistema", "visao_consolidada",
                "CLIENTE", null, cpfCnpj, conta, List.of("operacao", "originacao"), List.of(), filtro, null, 2,
                List.of(), Map.of("operacao", 1L), Map.of(), 200, null, 15);
    }
}
