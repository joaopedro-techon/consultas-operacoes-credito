package br.com.consultas.operacoescredito.config.resiliencia;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Prazo da requisição por tipo de consulta (fail fast → 504), acima dos P99 alvo (80 ms e 200 ms) para não
 * derrubar 1% das requisições por construção.
 *
 * @param operacao prazo de {@code OPERACAO}
 * @param cliente  prazo de {@code CLIENTE}
 * @param conta    prazo de {@code CONTA}
 */
@ConfigurationProperties("consultas.prazo")
public record PrazoProperties(
        @DefaultValue("150ms") Duration operacao,
        @DefaultValue("400ms") Duration cliente,
        @DefaultValue("400ms") Duration conta) {

    public PrazoProperties {
        positivo(operacao, "operacao");
        positivo(cliente, "cliente");
        positivo(conta, "conta");
    }

    public Duration de(TipoConsulta tipo) {
        return switch (tipo) {
            case OPERACAO -> operacao;
            case CLIENTE -> cliente;
            case CONTA -> conta;
        };
    }

    private static void positivo(Duration prazo, String nome) {
        if (prazo == null || prazo.isNegative() || prazo.isZero()) {
            throw new IllegalArgumentException("consultas.prazo." + nome + " deve ser positivo");
        }
    }
}
