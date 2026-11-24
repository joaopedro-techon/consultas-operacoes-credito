package br.com.consultas.operacoescredito.adapters.out.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cache por bloco da visão consolidada. A conexão com o Valkey (host, porta, timeouts) fica em
 * {@code spring.data.redis.*}; o circuit breaker em {@code resilience4j.circuitbreaker.instances.valkey}.
 *
 * @param habilitado              {@code false} desliga o cache (toda leitura vai ao DynamoDB)
 * @param ttl                     tempo de vida de cada bloco no cache
 * @param chavesPorComando        chaves por {@code MGET} e {@code SET}s por rodada de gravação: leituras e
 *                                gravações grandes viram vários comandos pequenos em pipeline, cada um dentro do
 *                                timeout de comando, sem segurar o Valkey (single-thread) num comando gigante
 * @param maxChavesPorConsulta    acima disso (operações × blocos) a consulta nem lê nem grava no cache: vai direto
 *                                ao DynamoDB ({@code resultado=ignorado})
 * @param prazoMinimoRestante     com menos que isso do prazo da requisição a leitura no cache é pulada
 *                                ({@code resultado=ignorado}); deve ser maior que {@code spring.data.redis.timeout}
 *                                para que um timeout do Valkey nunca seja encurtado pelo prazo
 * @param maxGravacoesSimultaneas gravações em andamento (fora da requisição, em virtual threads); com todas as
 *                                vagas ocupadas, a gravação nova é descartada (o cache é só otimização)
 * @param ttlCustodia             tempo de vida da custódia de uma operação no cache de custódia (ADR-0012); bem
 *                                maior que {@code ttl}, para que blocos em cache quase sempre tenham a custódia
 */
@ConfigurationProperties("consultas.cache")
public record CacheProperties(@DefaultValue("true") boolean habilitado,
                              @DefaultValue("5m") Duration ttl,
                              @DefaultValue("100") int chavesPorComando,
                              @DefaultValue("500") int maxChavesPorConsulta,
                              @DefaultValue("20ms") Duration prazoMinimoRestante,
                              @DefaultValue("32") int maxGravacoesSimultaneas,
                              @DefaultValue("24h") Duration ttlCustodia) {

    public CacheProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("consultas.cache.ttl deve ser positivo: " + ttl);
        }
        if (ttlCustodia.isNegative() || ttlCustodia.isZero()) {
            throw new IllegalArgumentException("consultas.cache.ttl-custodia deve ser positivo: " + ttlCustodia);
        }
        if (chavesPorComando < 1) {
            throw new IllegalArgumentException("consultas.cache.chaves-por-comando deve ser ao menos 1");
        }
        if (maxChavesPorConsulta < 1) {
            throw new IllegalArgumentException("consultas.cache.max-chaves-por-consulta deve ser ao menos 1");
        }
        if (prazoMinimoRestante.isNegative()) {
            throw new IllegalArgumentException("consultas.cache.prazo-minimo-restante não pode ser negativo");
        }
        if (maxGravacoesSimultaneas < 1) {
            throw new IllegalArgumentException("consultas.cache.max-gravacoes-simultaneas deve ser ao menos 1");
        }
    }
}
