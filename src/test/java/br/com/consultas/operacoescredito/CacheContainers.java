package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Valkey (mesma imagem do docker-compose) atrás de um Toxiproxy: a aplicação fala com o Valkey pelo proxy,
 * e o teste derruba ou atrasa o proxy para simular Valkey fora ou lento. O teste lê e limpa o Valkey direto
 * pela porta do container, sem passar pelo proxy. Liga o cache ({@code consultas.cache.habilitado=true}),
 * que nos demais testes fica desligado.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CacheContainers {

    static final String IMAGEM_VALKEY = "valkey/valkey:9.0.8-alpine";
    static final String IMAGEM_TOXIPROXY = "ghcr.io/shopify/toxiproxy:2.12.0";
    static final int PORTA_VALKEY = 6379;
    private static final String ALIAS_VALKEY = "valkey";
    /** Primeira das portas que o {@link ToxiproxyContainer} expõe para proxies. */
    private static final int PORTA_PROXY = 8666;

    @Bean
    Network redeCache() {
        return Network.newNetwork();
    }

    @Bean
    GenericContainer<?> valkey(Network redeCache) {
        return new GenericContainer<>(DockerImageName.parse(IMAGEM_VALKEY))
                .withNetwork(redeCache)
                .withNetworkAliases(ALIAS_VALKEY)
                .withExposedPorts(PORTA_VALKEY)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    @Bean
    ToxiproxyContainer toxiproxy(Network redeCache) {
        return new ToxiproxyContainer(DockerImageName.parse(IMAGEM_TOXIPROXY)).withNetwork(redeCache);
    }

    @Bean
    Proxy proxyValkey(ToxiproxyContainer toxiproxy, @Qualifier("valkey") GenericContainer<?> valkey) {
        try {
            return new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                    .createProxy("valkey", "0.0.0.0:" + PORTA_PROXY, ALIAS_VALKEY + ":" + PORTA_VALKEY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar cacheProperties(ToxiproxyContainer toxiproxy, Proxy proxyValkey) {
        return registry -> {
            registry.add("consultas.cache.habilitado", () -> "true");
            registry.add("spring.data.redis.host", toxiproxy::getHost);
            registry.add("spring.data.redis.port", () -> toxiproxy.getMappedPort(PORTA_PROXY));
        };
    }

    /** URI do Valkey sem o proxy, para o teste inspecionar o cache. */
    static String uriDireta(GenericContainer<?> valkey) {
        return "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(PORTA_VALKEY);
    }
}
