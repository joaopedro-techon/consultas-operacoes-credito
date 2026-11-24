package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Conexão com a fonte primária (gateway do mainframe).
 *
 * @param url     URL base do gateway
 * @param layouts diretório dos layouts de trancode ({@code <bloco>.yml})
 * @param pool    pool de conexões do Apache HttpClient 5
 */
@ConfigurationProperties("consultas.fonte-primaria")
public record FontePrimariaProperties(
        String url,
        @DefaultValue("classpath:trancode/layouts/") String layouts,
        @DefaultValue Pool pool) {

    /**
     * @param maxConexoes          máximo de conexões abertas no total
     * @param maxConexoesPorRota   máximo de conexões abertas para o gateway
     * @param ttl                  tempo de vida de uma conexão
     * @param keepAlive            tempo máximo que uma conexão ociosa é mantida
     * @param timeoutConexao       prazo para abrir a conexão TCP
     * @param timeoutObterConexao  prazo para obter uma conexão do pool
     * @param timeoutResposta      prazo para receber a resposta
     */
    public record Pool(
            @DefaultValue("50") int maxConexoes,
            @DefaultValue("50") int maxConexoesPorRota,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("30s") Duration keepAlive,
            @DefaultValue("200ms") Duration timeoutConexao,
            @DefaultValue("50ms") Duration timeoutObterConexao,
            @DefaultValue("1s") Duration timeoutResposta) {
    }
}
