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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;

/** Cache por bloco da visão consolidada: Valkey ou, com {@code consultas.cache.habilitado=false}, desligado. */
@Configuration(proxyBeanMethods = false)
class CacheConfig {

    /** Instância do circuit breaker do Valkey em {@code resilience4j.circuitbreaker.instances}. */
    static final String CIRCUIT_BREAKER = "valkey";

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    /** Timeout de comando quando a fábrica de conexões não é a do Lettuce (o mesmo do {@code application.yml}). */
    private static final Duration TIMEOUT_COMANDO_PADRAO = Duration.ofMillis(10);

    @Bean
    CacheDeBlocos cacheDeBlocos(CacheProperties properties, ObjectProvider<RedisConnectionFactory> conexoes,
                                CircuitBreakerRegistry circuitBreakers, ObservationRegistry observationRegistry,
                                MeterRegistry meterRegistry) {
        if (!properties.habilitado()) {
            log.info("Cache por bloco desligado (consultas.cache.habilitado=false)");
            return new CacheDesligado();
        }
        var fabrica = conexoes.getObject();
        var timeoutComando = fabrica instanceof LettuceConnectionFactory lettuce
                ? lettuce.getClientConfiguration().getCommandTimeout()
                : TIMEOUT_COMANDO_PADRAO;
        if (properties.prazoMinimoRestante().compareTo(timeoutComando) <= 0) {
            log.warn("consultas.cache.prazo-minimo-restante ({}) não passa de spring.data.redis.timeout ({}): "
                    + "timeouts do Valkey encurtados pelo prazo da requisição contariam no circuito",
                    properties.prazoMinimoRestante(), timeoutComando);
        }
        return new ValkeyCacheDeBlocos(ComandosValkey.lettuce(fabrica),
                circuitBreakers.circuitBreaker(CIRCUIT_BREAKER), observationRegistry, meterRegistry, properties,
                timeoutComando, Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("valkey-gravacao-", 0).factory()));
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
