package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexão com a visão consolidada.
 *
 * @param tabela      nome da tabela (ADR-0001)
 * @param regiao      região AWS
 * @param endpoint    endpoint alternativo (LocalStack); vazio usa o endpoint padrão da AWS
 * @param credenciais credenciais fixas (só ambiente local); ausentes usam a cadeia padrão do SDK
 */
@ConfigurationProperties("consultas.dynamodb")
public record DynamoDbProperties(String tabela, String regiao, String endpoint, Credenciais credenciais) {

    public record Credenciais(String accessKeyId, String secretAccessKey) {
    }
}
