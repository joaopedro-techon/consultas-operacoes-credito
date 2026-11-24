package br.com.consultas.operacoescredito.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.StandardCharsets;

/**
 * Evento de consumo (ADR-0004), parte do caso de uso. A fila em memória e o SQS ficam em
 * {@code consultas.eventos.fila.*} e {@code consultas.eventos.sqs.*}, lidos pelo adapter.
 *
 * @param habilitado  {@code false} desliga o evento de consumo (nada é montado nem enviado)
 * @param segredoHmac segredo do HMAC-SHA256 de CPF/CNPJ e conta; obrigatório com o evento ligado, com pelo menos
 *                    {@value #TAMANHO_MINIMO_SEGREDO} bytes em UTF-8. Nunca aparece no {@link #toString()}
 */
@ConfigurationProperties("consultas.eventos")
public record EventosDeConsumoProperties(@DefaultValue("true") boolean habilitado, String segredoHmac) {

    /** Menor segredo aceito, em bytes (256 bits). */
    public static final int TAMANHO_MINIMO_SEGREDO = 32;

    private static final String PROPRIEDADE = "consultas.eventos.segredo-hmac (CONSULTAS_EVENTOS_SEGREDO_HMAC)";

    public EventosDeConsumoProperties {
        if (habilitado && (segredoHmac == null || segredoHmac.isBlank())) {
            throw new IllegalArgumentException(PROPRIEDADE + " é obrigatório com o evento de consumo ligado; "
                    + "consultas.eventos.habilitado=false o desliga");
        }
        if (habilitado && segredoHmac.getBytes(StandardCharsets.UTF_8).length < TAMANHO_MINIMO_SEGREDO) {
            throw new IllegalArgumentException(PROPRIEDADE + " deve ter pelo menos " + TAMANHO_MINIMO_SEGREDO
                    + " bytes (256 bits); gere um valor aleatório, ex.: openssl rand -base64 48");
        }
    }

    /** Sem o segredo: só indica se ele foi informado. */
    @Override
    public String toString() {
        return "EventosDeConsumoProperties[habilitado=" + habilitado + ", segredoHmac="
                + (segredoHmac == null || segredoHmac.isEmpty() ? "<vazio>" : "******") + "]";
    }
}
