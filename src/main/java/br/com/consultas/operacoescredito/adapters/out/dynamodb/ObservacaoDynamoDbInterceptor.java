package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Um span e um timer ({@code consultas.dependencia}, {@code dependencia=dynamodb}) por chamada ao
 * DynamoDB, com a operação ({@code BatchGetItem}, {@code Query}...) e o GSI usado ({@code indice}).
 * Fica no cliente do SDK, então cobre toda chamada do adapter sem tocar no código de leitura.
 */
class ObservacaoDynamoDbInterceptor implements ExecutionInterceptor {

    static final String DEPENDENCIA = "dynamodb";
    /** Valor da tag {@code indice} nas chamadas à tabela base. */
    static final String SEM_INDICE = "nenhum";
    private static final ExecutionAttribute<Observation> OBSERVACAO = new ExecutionAttribute<>("consultas.observacao");

    private final ObservationRegistry registry;

    ObservacaoDynamoDbInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes atributos) {
        var operacao = atributos.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
        var indice = context.request() instanceof QueryRequest query && query.indexName() != null
                ? query.indexName()
                : SEM_INDICE;
        var observacao = Observacoes.dependencia(registry, DEPENDENCIA, operacao == null ? "desconhecida" : operacao)
                .lowCardinalityKeyValue("indice", indice)
                .start();
        atributos.putAttribute(OBSERVACAO, observacao);
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes atributos) {
        var observacao = atributos.getAttribute(OBSERVACAO);
        if (observacao != null) {
            observacao.stop();
        }
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes atributos) {
        var observacao = atributos.getAttribute(OBSERVACAO);
        if (observacao != null) {
            observacao.error(context.exception());
            observacao.stop();
        }
    }
}
