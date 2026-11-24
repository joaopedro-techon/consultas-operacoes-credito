package br.com.consultas.operacoescredito;

import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * LocalStack com a mesma imagem, o mesmo script de init e a mesma massa do docker-compose.
 * Não existe {@code @ServiceConnection} para LocalStack no Spring Boot, então as propriedades
 * são registradas com {@link DynamicPropertyRegistrar}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class VisaoConsolidadaContainers {

    static final String IMAGEM_LOCALSTACK = "localstack/localstack:4.14.0";

    @Bean
    LocalStackContainer localStack() {
        return new LocalStackContainer(DockerImageName.parse(IMAGEM_LOCALSTACK))
                .withEnv("SERVICES", "dynamodb,sqs")
                .withEnv("CONSULTAS_DYNAMODB_TABELA", "operacoes-credito")
                // Fila dos eventos de consumo; o script roda antes do da visão consolidada
                .withCopyFileToContainer(
                        MountableFile.forHostPath("localstack/init/ready.d/00-eventos-consumo.sh", 0755),
                        "/etc/localstack/init/ready.d/00-eventos-consumo.sh")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("localstack/init/ready.d/01-visao-consolidada.sh", 0755),
                        "/etc/localstack/init/ready.d/01-visao-consolidada.sh")
                .withCopyFileToContainer(MountableFile.forHostPath("localstack/massa"), "/etc/localstack/massa")
                // A massa tem dezenas de PutItem via awslocal (~0,2 s cada); o default de 60 s não basta.
                .waitingFor(Wait.forLogMessage(".*VISAO_CONSOLIDADA_PRONTA.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(4)));
    }

    @Bean
    DynamicPropertyRegistrar visaoConsolidadaProperties(LocalStackContainer localStack) {
        return registry -> {
            registry.add("consultas.dynamodb.endpoint", () -> localStack.getEndpoint().toString());
            registry.add("consultas.dynamodb.regiao", localStack::getRegion);
            registry.add("consultas.dynamodb.tabela", () -> "operacoes-credito");
            registry.add("consultas.dynamodb.credenciais.access-key-id", localStack::getAccessKey);
            registry.add("consultas.dynamodb.credenciais.secret-access-key", localStack::getSecretKey);
        };
    }
}
