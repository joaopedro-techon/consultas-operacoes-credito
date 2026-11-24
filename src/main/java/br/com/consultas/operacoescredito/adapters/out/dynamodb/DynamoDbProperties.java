package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Conexão com a visão consolidada.
 *
 * @param tabela           nome da tabela (ADR-0001)
 * @param regiao           região AWS
 * @param endpoint         endpoint alternativo (LocalStack); vazio usa o endpoint padrão da AWS
 * @param credenciais      credenciais fixas (só ambiente local); ausentes usam a cadeia padrão do SDK
 * @param indiceCliente    GSI esparso por cliente ({@code cpf_cnpj_cliente})
 * @param indiceConta      GSI esparso por conta ({@code conta_cliente})
 * @param timeoutChamada   teto de uma chamada ao SDK com todas as tentativas ({@code apiCallTimeout}); numa
 *                         requisição, vale o menor entre ele e o que resta do prazo da requisição
 * @param timeoutTentativa teto de cada tentativa ({@code apiCallAttemptTimeout}), para que uma tentativa presa
 *                         ainda deixe prazo para o retry
 * @param maxLotesSimultaneos lotes de {@code BatchGetItem} em andamento ao mesmo tempo numa consulta CLIENTE/CONTA
 *                         (cada um ocupa uma conexão do pool); os demais esperam vaga dentro do prazo
 * @param pool             cliente HTTP do SDK (Apache HttpClient 5)
 * @param retry            retry do SDK, a única camada de retry do DynamoDB
 */
@ConfigurationProperties("consultas.dynamodb")
public record DynamoDbProperties(String tabela, String regiao, String endpoint, Credenciais credenciais,
                                 @DefaultValue("gsi-cliente") String indiceCliente,
                                 @DefaultValue("gsi-conta") String indiceConta,
                                 @DefaultValue("1s") Duration timeoutChamada,
                                 @DefaultValue("100ms") Duration timeoutTentativa,
                                 @DefaultValue("4") int maxLotesSimultaneos,
                                 @DefaultValue Pool pool,
                                 @DefaultValue Retry retry) {

    public DynamoDbProperties {
        if (maxLotesSimultaneos < 1) {
            throw new IllegalArgumentException("consultas.dynamodb.max-lotes-simultaneos deve ser ao menos 1");
        }
    }

    public record Credenciais(String accessKeyId, String secretAccessKey) {
    }

    /**
     * @param maxConexoes        conexões no pool; deve comportar o pior caso dos bulkheads (regra em
     *                           {@code DynamoDbConfig#conexoesNecessarias}): cada consulta OPERACAO usa uma conexão
     *                           por vez e cada CLIENTE/CONTA até {@code max-lotes-simultaneos}
     * @param timeoutConexao     abertura da conexão TCP
     * @param timeoutObterConexao espera por uma conexão livre do pool
     * @param timeoutResposta    leitura do socket
     * @param ttl                vida máxima de uma conexão (renova DNS e balanceamento)
     * @param maxOciosa          conexão ociosa por mais que isso é fechada
     */
    public record Pool(@DefaultValue("100") int maxConexoes,
                       @DefaultValue("100ms") Duration timeoutConexao,
                       @DefaultValue("20ms") Duration timeoutObterConexao,
                       @DefaultValue("1s") Duration timeoutResposta,
                       @DefaultValue("5m") Duration ttl,
                       @DefaultValue("30s") Duration maxOciosa) {

        public Pool {
            if (maxConexoes < 1) {
                throw new IllegalArgumentException("consultas.dynamodb.pool.max-conexoes deve ser ao menos 1");
            }
        }
    }

    /**
     * Modo {@code standard} do SDK: só erros retentáveis (throttling, 5xx, falha de conexão), backoff exponencial
     * com jitter.
     *
     * @param tentativasExtras tentativas além da primeira
     * @param backoffBase      base do backoff exponencial
     * @param backoffMaximo    teto do backoff
     */
    public record Retry(@DefaultValue("2") int tentativasExtras,
                        @DefaultValue("10ms") Duration backoffBase,
                        @DefaultValue("50ms") Duration backoffMaximo) {

        public Retry {
            if (tentativasExtras < 0) {
                throw new IllegalArgumentException("consultas.dynamodb.retry.tentativas-extras não pode ser negativo");
            }
        }
    }
}
