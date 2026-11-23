package pl.piomin.services.adapters.out.dynamodb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
class DynamoDbConfig {

    @Bean
    DynamoDbClient dynamoDbClient(DynamoDbProperties properties) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(properties.regiao()))
                .credentialsProvider(credenciais(properties.credenciais()));
        if (StringUtils.hasText(properties.endpoint())) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credenciais(DynamoDbProperties.Credenciais credenciais) {
        if (credenciais != null
                && StringUtils.hasText(credenciais.accessKeyId())
                && StringUtils.hasText(credenciais.secretAccessKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(credenciais.accessKeyId(), credenciais.secretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
