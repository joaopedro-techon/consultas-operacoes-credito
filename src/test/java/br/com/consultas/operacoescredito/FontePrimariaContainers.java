package br.com.consultas.operacoescredito;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Gateway do mainframe simulado no WireMock, com a mesma imagem e os mesmos stubs de trancode do
 * docker-compose ({@code wiremock/mappings}, gerados por {@code localstack/gerar_trancode.py}).
 * Não existe {@code @ServiceConnection} para WireMock, então a URL é registrada com
 * {@link DynamicPropertyRegistrar}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FontePrimariaContainers {

    static final String IMAGEM_WIREMOCK = "wiremock/wiremock:3.13.2";
    static final int PORTA_WIREMOCK = 8080;

    @Bean
    GenericContainer<?> wireMock() {
        return new GenericContainer<>(DockerImageName.parse(IMAGEM_WIREMOCK))
                .withExposedPorts(PORTA_WIREMOCK)
                .withCopyFileToContainer(MountableFile.forHostPath("wiremock/mappings"), "/home/wiremock/mappings")
                .withCommand("--disable-banner")
                .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));
    }

    @Bean
    DynamicPropertyRegistrar fontePrimariaProperties(@Qualifier("wireMock") GenericContainer<?> wireMock) {
        return registry -> registry.add("consultas.fonte-primaria.url", () -> urlWireMock(wireMock));
    }

    static String urlWireMock(GenericContainer<?> wireMock) {
        return "http://" + wireMock.getHost() + ":" + wireMock.getMappedPort(PORTA_WIREMOCK);
    }
}
