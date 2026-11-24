package br.com.consultas.operacoescredito.config.resiliencia;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazo da requisição por rota e tipo de consulta (fail fast → 504). Na visão consolidada, acima dos P99 alvo
 * (80 ms e 200 ms) para não derrubar 1% das requisições por construção. Na fonte primária, acima das rodadas
 * sequenciais de chamadas ao mainframe (descoberta de custódia, blocos, páginas), com p99 de 500 ms por chamada.
 *
 * @param operacao      prazo de {@code OPERACAO} na visão consolidada
 * @param cliente       prazo de {@code CLIENTE} na visão consolidada
 * @param conta         prazo de {@code CONTA} na visão consolidada
 * @param fontePrimaria prazos da rota da fonte primária
 */
@ConfigurationProperties("consultas.prazo")
public record PrazoProperties(
        @DefaultValue("150ms") Duration operacao,
        @DefaultValue("400ms") Duration cliente,
        @DefaultValue("400ms") Duration conta,
        @DefaultValue FontePrimaria fontePrimaria) {

    public PrazoProperties {
        positivo(operacao, "operacao");
        positivo(cliente, "cliente");
        positivo(conta, "conta");
    }

    /** Prazo da visão consolidada. */
    public Duration de(TipoConsulta tipo) {
        return switch (tipo) {
            case OPERACAO -> operacao;
            case CLIENTE -> cliente;
            case CONTA -> conta;
        };
    }

    /** Prazo da rota pedida: fonte primária ou visão consolidada. */
    public Duration de(TipoConsulta tipo, boolean naFontePrimaria) {
        return naFontePrimaria ? fontePrimaria.de(tipo) : de(tipo);
    }

    /**
     * @param operacao prazo de {@code OPERACAO} na fonte primária
     * @param cliente  prazo de {@code CLIENTE} na fonte primária
     * @param conta    prazo de {@code CONTA} na fonte primária
     */
    public record FontePrimaria(
            @DefaultValue("2500ms") Duration operacao,
            @DefaultValue("6s") Duration cliente,
            @DefaultValue("6s") Duration conta) {

        public FontePrimaria {
            positivo(operacao, "fonte-primaria.operacao");
            positivo(cliente, "fonte-primaria.cliente");
            positivo(conta, "fonte-primaria.conta");
        }

        public Duration de(TipoConsulta tipo) {
            return switch (tipo) {
                case OPERACAO -> operacao;
                case CLIENTE -> cliente;
                case CONTA -> conta;
            };
        }
    }

    private static void positivo(Duration prazo, String nome) {
        if (prazo == null || prazo.isNegative() || prazo.isZero()) {
            throw new IllegalArgumentException("consultas.prazo." + nome + " deve ser positivo");
        }
    }
}
