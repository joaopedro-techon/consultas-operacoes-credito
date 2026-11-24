package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conexão com a visão consolidada.
 *
 * @param tabela        nome da tabela (ADR-0001)
 * @param regiao        região AWS
 * @param endpoint      endpoint alternativo (LocalStack); vazio usa o endpoint padrão da AWS
 * @param credenciais   credenciais fixas (só ambiente local); ausentes usam a cadeia padrão do SDK
 * @param indiceCliente GSI esparso por cliente ({@code cpf_cnpj_cliente})
 * @param indiceConta   GSI esparso por conta ({@code conta_cliente})
 */
@ConfigurationProperties("consultas.dynamodb")
public record DynamoDbProperties(String tabela, String regiao, String endpoint, Credenciais credenciais,
                                 @DefaultValue("gsi-cliente") String indiceCliente,
                                 @DefaultValue("gsi-conta") String indiceConta) {

    public record Credenciais(String accessKeyId, String secretAccessKey) {
    }
}
