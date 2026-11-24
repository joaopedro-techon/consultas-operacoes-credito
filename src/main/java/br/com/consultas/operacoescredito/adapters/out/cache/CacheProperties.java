package br.com.consultas.operacoescredito.adapters.out.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cache por bloco da visão consolidada. A conexão com o Valkey (host, porta, timeout) fica em
 * {@code spring.data.redis.*}; o circuit breaker em {@code resilience4j.circuitbreaker.instances.valkey}.
 *
 * @param habilitado {@code false} desliga o cache (toda leitura vai ao DynamoDB)
 * @param ttl        tempo de vida de cada bloco no cache
 */
@ConfigurationProperties("consultas.cache")
public record CacheProperties(@DefaultValue("true") boolean habilitado,
                              @DefaultValue("5m") Duration ttl) {

    public CacheProperties {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("consultas.cache.ttl deve ser positivo: " + ttl);
        }
    }
}
