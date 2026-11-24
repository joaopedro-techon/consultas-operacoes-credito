package br.com.consultas.operacoescredito;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Network;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * LocalStack da visão consolidada atrás de um Toxiproxy: a aplicação fala com o DynamoDB pelo proxy, e o teste
 * atrasa ou derruba o proxy para simular DynamoDB lento ou fora. Contexto próprio (só os testes de resiliência o
 * importam), para que a latência injetada não alcance os demais testes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class VisaoConsolidadaComToxiproxyContainers {

    private static final String ALIAS_LOCALSTACK = "localstack";
    private static final int PORTA_LOCALSTACK = 4566;
    /** Primeira das portas que o {@link ToxiproxyContainer} expõe para proxies. */
    private static final int PORTA_PROXY = 8666;

    @Bean
    Network redeVisaoConsolidada() {
        return Network.newNetwork();
    }

    @Bean
    LocalStackContainer localStack(Network redeVisaoConsolidada) {
        return VisaoConsolidadaContainers.novoLocalStack()
                .withNetwork(redeVisaoConsolidada)
                .withNetworkAliases(ALIAS_LOCALSTACK);
    }

    @Bean
    ToxiproxyContainer toxiproxyDynamoDb(Network redeVisaoConsolidada) {
        return new ToxiproxyContainer(DockerImageName.parse(CacheContainers.IMAGEM_TOXIPROXY))
                .withNetwork(redeVisaoConsolidada);
    }

    @Bean
    Proxy proxyDynamoDb(ToxiproxyContainer toxiproxyDynamoDb, LocalStackContainer localStack) {
        try {
            return new ToxiproxyClient(toxiproxyDynamoDb.getHost(), toxiproxyDynamoDb.getControlPort())
                    .createProxy("dynamodb", "0.0.0.0:" + PORTA_PROXY, ALIAS_LOCALSTACK + ":" + PORTA_LOCALSTACK);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Bean
    DynamicPropertyRegistrar visaoConsolidadaComToxiproxyProperties(ToxiproxyContainer toxiproxyDynamoDb,
                                                                   LocalStackContainer localStack,
                                                                   Proxy proxyDynamoDb) {
        return registry -> {
            registry.add("consultas.dynamodb.endpoint", () -> "http://" + toxiproxyDynamoDb.getHost() + ":"
                    + toxiproxyDynamoDb.getMappedPort(PORTA_PROXY));
            registry.add("consultas.dynamodb.regiao", localStack::getRegion);
            registry.add("consultas.dynamodb.tabela", () -> "operacoes-credito");
            registry.add("consultas.dynamodb.credenciais.access-key-id", localStack::getAccessKey);
            registry.add("consultas.dynamodb.credenciais.secret-access-key", localStack::getSecretKey);
        };
    }
}
