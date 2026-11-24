package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Conexão com a fonte primária (gateway do mainframe).
 *
 * @param url     URL base do gateway
 * @param layouts diretório dos layouts de trancode ({@code <bloco>.yml} e {@code lista_operacoes.yml})
 * @param pool    pool de conexões do Apache HttpClient 5
 * @param fanOut  chamadas paralelas ao gateway em uma mesma requisição
 * @param retry   condição de prazo do retry (tentativas e backoff ficam em
 *                {@code resilience4j.retry.instances.fontePrimaria})
 */
@ConfigurationProperties("consultas.fonte-primaria")
public record FontePrimariaProperties(
        String url,
        @DefaultValue("classpath:trancode/layouts/") String layouts,
        @DefaultValue Pool pool,
        @DefaultValue FanOut fanOut,
        @DefaultValue Retry retry) {

    /**
     * @param maxConexoes          máximo de conexões abertas no total
     * @param maxConexoesPorRota   máximo de conexões abertas para o gateway
     * @param ttl                  tempo de vida de uma conexão
     * @param keepAlive            tempo máximo que uma conexão ociosa é mantida
     * @param timeoutConexao       prazo para abrir a conexão TCP; bem abaixo do menor prazo da requisição (OPERACAO,
     *                             150 ms) para que um host inalcançável estoure este timeout (conta no circuito e é
     *                             retentado) e não o prazo. Como os demais, é limitado ao que resta do prazo
     * @param timeoutObterConexao  prazo para obter uma conexão do pool
     * @param timeoutResposta      prazo para receber a resposta
     */
    public record Pool(
            @DefaultValue("50") int maxConexoes,
            @DefaultValue("50") int maxConexoesPorRota,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("30s") Duration keepAlive,
            @DefaultValue("50ms") Duration timeoutConexao,
            @DefaultValue("50ms") Duration timeoutObterConexao,
            @DefaultValue("1s") Duration timeoutResposta) {
    }

    /**
     * @param maxChamadasSimultaneas teto de chamadas ao gateway em andamento ao mesmo tempo por requisição
     *                               (operação × bloco); as demais esperam a vez
     */
    public record FanOut(@DefaultValue("16") int maxChamadasSimultaneas) {

        public FanOut {
            if (maxChamadasSimultaneas < 1) {
                throw new IllegalArgumentException(
                        "consultas.fonte-primaria.fan-out.max-chamadas-simultaneas deve ser ao menos 1");
            }
        }
    }

    /**
     * @param prazoMinimoRestante só tenta de novo se ainda restar pelo menos isto do prazo da requisição: cobre o
     *                            backoff (até 30 ms com os valores padrão: 10 ms, ×2, jitter de 50%) e uma
     *                            tentativa rápida
     */
    public record Retry(@DefaultValue("50ms") Duration prazoMinimoRestante) {

        public Retry {
            if (prazoMinimoRestante.isNegative()) {
                throw new IllegalArgumentException(
                        "consultas.fonte-primaria.retry.prazo-minimo-restante não pode ser negativo");
            }
        }
    }
}