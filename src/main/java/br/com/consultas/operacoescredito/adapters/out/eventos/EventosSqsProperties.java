package br.com.consultas.operacoescredito.adapters.out.eventos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Publicação dos eventos de consumo no SQS (ADR-0004). O segredo do HMAC é do caso de uso
 * ({@code consultas.eventos.segredo-hmac}); circuit breaker e bulkhead em {@code resilience4j.*.instances.sqs}.
 *
 * @param habilitado {@code false} desliga a publicação (sem cliente SQS nem thread de envio)
 * @param fila       fila em memória e lotes
 * @param sqs        conexão com o SQS
 */
@ConfigurationProperties("consultas.eventos")
public record EventosSqsProperties(@DefaultValue("true") boolean habilitado, @DefaultValue Fila fila,
                                   @DefaultValue Sqs sqs) {

    /** Máximo de mensagens num {@code SendMessageBatch}. */
    static final int LOTE_MAXIMO_SQS = 10;

    public EventosSqsProperties {
        if (habilitado && (sqs.urlFila() == null || sqs.urlFila().isBlank())) {
            throw new IllegalArgumentException("consultas.eventos.sqs.url-fila (CONSULTAS_EVENTOS_SQS_URL_FILA) é "
                    + "obrigatório com o evento de consumo ligado");
        }
    }

    /**
     * @param capacidade           eventos que cabem na fila em memória; cheia, o evento novo é descartado
     * @param tamanhoLote          eventos por {@code SendMessageBatch} (1 a 10)
     * @param intervaloEnvio       espera máxima para completar um lote depois do primeiro evento
     * @param tempoMaximoDrenagem  quanto o encerramento gracioso espera para enviar o que restou na fila
     */
    public record Fila(@DefaultValue("10000") int capacidade, @DefaultValue("10") int tamanhoLote,
                       @DefaultValue("1s") Duration intervaloEnvio, @DefaultValue("10s") Duration tempoMaximoDrenagem) {

        public Fila {
            if (capacidade < 1) {
                throw new IllegalArgumentException("consultas.eventos.fila.capacidade deve ser positiva: " + capacidade);
            }
            if (tamanhoLote < 1 || tamanhoLote > LOTE_MAXIMO_SQS) {
                throw new IllegalArgumentException(
                        "consultas.eventos.fila.tamanho-lote deve ser de 1 a " + LOTE_MAXIMO_SQS + ": " + tamanhoLote);
            }
            positiva("consultas.eventos.fila.intervalo-envio", intervaloEnvio);
            positiva("consultas.eventos.fila.tempo-maximo-drenagem", tempoMaximoDrenagem);
        }
    }

    /**
     * @param urlFila          URL da fila
     * @param endpoint         endpoint alternativo (LocalStack); vazio usa o endpoint padrão da AWS
     * @param regiao           região AWS
     * @param credenciais      credenciais fixas (só ambiente local); ausentes usam a cadeia padrão do SDK
     * @param timeoutTentativa limite de cada tentativa do SDK
     * @param timeoutChamada   limite da chamada inteira, com os retries do SDK
     */
    public record Sqs(String urlFila, String endpoint, @DefaultValue("sa-east-1") String regiao,
                      Credenciais credenciais, @DefaultValue("1s") Duration timeoutTentativa,
                      @DefaultValue("3s") Duration timeoutChamada) {

        public Sqs {
            positiva("consultas.eventos.sqs.timeout-tentativa", timeoutTentativa);
            positiva("consultas.eventos.sqs.timeout-chamada", timeoutChamada);
        }
    }

    public record Credenciais(String accessKeyId, String secretAccessKey) {
    }

    private static void positiva(String propriedade, Duration valor) {
        if (valor.isNegative() || valor.isZero()) {
            throw new IllegalArgumentException(propriedade + " deve ser positivo: " + valor);
        }
    }
}
