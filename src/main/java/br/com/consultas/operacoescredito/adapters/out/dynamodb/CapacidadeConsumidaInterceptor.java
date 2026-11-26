package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;

import java.util.List;

/**
 * Capacidade de leitura que cada chamada ao DynamoDB consumiu ({@value #METRICA}, em RRU/RCU), para comparar o custo
 * dos caminhos de leitura com dados reais (ADR-0020). Pede {@code ReturnConsumedCapacity=TOTAL} em toda
 * {@code Query} e {@code BatchGetItem}, sem custo extra, e registra o que a resposta informa. Fica no cliente do SDK,
 * então mede toda leitura do adapter sem tocar no código dele.
 * <p>
 * Como ler as tags {@code operacao} e {@code indice}:
 * <ul>
 *     <li>{@code Query} + {@code nenhum}: descoberta de custódia da consulta OPERACAO (Query pela PK na tabela
 *     base);</li>
 *     <li>{@code Query} + {@code gsi-cliente}/{@code gsi-conta}: listagem das operações de CLIENTE/CONTA;</li>
 *     <li>{@code BatchGetItem} + {@code nenhum}: blocos na custódia já conhecida e releituras consistentes.</li>
 * </ul>
 * Só a resposta final de cada chamada entra na métrica: as tentativas que o retry do SDK repetiu ficam de fora.
 */
class CapacidadeConsumidaInterceptor implements ExecutionInterceptor {

    static final String METRICA = "consultas.dynamodb.capacidade_consumida";

    private final MeterRegistry registry;

    CapacidadeConsumidaInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes atributos) {
        return switch (context.request()) {
            case QueryRequest query -> query.toBuilder()
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();
            case BatchGetItemRequest batch -> batch.toBuilder()
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                    .build();
            default -> context.request();
        };
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes atributos) {
        switch (context.response()) {
            case QueryResponse query when query.consumedCapacity() != null ->
                    registrar("Query", indice(context.request()), unidades(query.consumedCapacity()));
            case BatchGetItemResponse batch when batch.hasConsumedCapacity() ->
                    registrar("BatchGetItem", ObservacaoDynamoDbInterceptor.SEM_INDICE,
                            soma(batch.consumedCapacity()));
            default -> {
                // Outras operações (e respostas sem capacidade informada) não entram na métrica
            }
        }
    }

    private void registrar(String operacao, String indice, double unidades) {
        DistributionSummary.builder(METRICA)
                .description("Capacidade de leitura consumida por chamada ao DynamoDB")
                .baseUnit("capacity_units")
                .tag("operacao", operacao)
                .tag("indice", indice)
                .register(registry)
                .record(unidades);
    }

    private static String indice(SdkRequest requisicao) {
        return requisicao instanceof QueryRequest query && query.indexName() != null
                ? query.indexName()
                : ObservacaoDynamoDbInterceptor.SEM_INDICE;
    }

    /** O {@code BatchGetItem} informa a capacidade por tabela; aqui só há uma, mas somar não depende disso. */
    private static double soma(List<ConsumedCapacity> porTabela) {
        return porTabela.stream().mapToDouble(CapacidadeConsumidaInterceptor::unidades).sum();
    }

    private static double unidades(ConsumedCapacity capacidade) {
        return capacidade.capacityUnits() == null ? 0 : capacidade.capacityUnits();
    }
}
