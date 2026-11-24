package br.com.consultas.operacoescredito.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Evento de consumo (ADR-0004), parte do caso de uso. A fila em memória e o SQS ficam em
 * {@code consultas.eventos.fila.*} e {@code consultas.eventos.sqs.*}, lidos pelo adapter.
 *
 * @param habilitado  {@code false} desliga o evento de consumo (nada é montado nem enviado)
 * @param segredoHmac segredo do HMAC-SHA256 de CPF/CNPJ e conta; obrigatório com o evento ligado
 */
@ConfigurationProperties("consultas.eventos")
public record EventosDeConsumoProperties(@DefaultValue("true") boolean habilitado, String segredoHmac) {

    public EventosDeConsumoProperties {
        if (habilitado && (segredoHmac == null || segredoHmac.isBlank())) {
            throw new IllegalArgumentException("consultas.eventos.segredo-hmac (CONSULTAS_EVENTOS_SEGREDO_HMAC) é "
                    + "obrigatório com o evento de consumo ligado; consultas.eventos.habilitado=false o desliga");
        }
    }
}
