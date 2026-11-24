package br.com.consultas.operacoescredito.adapters.out.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Collection;
import java.util.Map;

/** Cache por bloco da visão consolidada: Valkey ou, com {@code consultas.cache.habilitado=false}, desligado. */
@Configuration(proxyBeanMethods = false)
class CacheConfig {

    /** Instância do circuit breaker do Valkey em {@code resilience4j.circuitbreaker.instances}. */
    static final String CIRCUIT_BREAKER = "valkey";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    CacheDeBlocos cacheDeBlocos(CacheProperties properties, ObjectProvider<RedisConnectionFactory> conexoes,
                                CircuitBreakerRegistry circuitBreakers, ObservationRegistry observationRegistry,
                                MeterRegistry meterRegistry) {
        if (!properties.habilitado()) {
            log.info("Cache por bloco desligado (consultas.cache.habilitado=false)");
            return new CacheDesligado();
        }
        return new ValkeyCacheDeBlocos(conexoes.getObject(), circuitBreakers.circuitBreaker(CIRCUIT_BREAKER),
                observationRegistry, meterRegistry, properties);
    }

    /** Nada em cache, nada gravado. */
    static final class CacheDesligado implements CacheDeBlocos {

        @Override
        public Map<ChaveDeBloco, byte[]> buscar(Collection<ChaveDeBloco> chaves) {
            return Map.of();
        }

        @Override
        public void gravar(Map<ChaveDeBloco, byte[]> blocos) {
            // desligado
        }
    }
}
