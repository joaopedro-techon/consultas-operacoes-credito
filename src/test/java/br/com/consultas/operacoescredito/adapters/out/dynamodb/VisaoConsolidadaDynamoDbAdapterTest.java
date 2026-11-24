package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.ChaveDeBloco;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter da visão consolidada contra um DynamoDB em memória que responde, atrasa ou falha como o teste mandar:
 * teto de lotes simultâneos por requisição, falha rápida de um lote qualquer, {@code UnprocessedKeys} esgotadas
 * e itens fora do formato. Sem cache.
 */
class VisaoConsolidadaDynamoDbAdapterTest {

    private static final String CLIENTE = "52998224725";
    private static final byte[] JSON_VAZIO = gzip("{}");

    private final DynamoDbFalso dynamoDb = new DynamoDbFalso();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("dynamodb", circuito());

    @Test
    void lotesDeUmaConsultaNaoPassamDoTetoDeLotesSimultaneos() {
        // 300 operações × 2 blocos fora do GSI = 600 chaves = 6 lotes de 100
        dynamoDb.operacoesNoIndice(300);
        dynamoDb.demoraDoBatch(Duration.ofMillis(40));
        var adapter = adapter(2);

        var operacoes = adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS, Bloco.SALDO));

        assertThat(operacoes).hasSize(300);
        assertThat(dynamoDb.chamadasBatch()).isEqualTo(6);
        assertThat(dynamoDb.maximoEmAndamento()).isEqualTo(2);
    }

    @Test
    void umLoteQueFalhaDerrubaAConsultaSemEsperarOsAnterioresEInterrompeOsDemais() {
        dynamoDb.operacoesNoIndice(150);
        var lentoInterrompido = new AtomicBoolean();
        dynamoDb.responderBatch(requisicao -> {
            var primeiraOperacao = Long.parseLong(chaves(requisicao).getFirst().get(VisaoConsolidadaDynamoDbAdapter.PK).n());
            if (primeiraOperacao == 1) {
                // Primeiro lote: lento
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    lentoInterrompido.set(true);
                    throw AbortedException.create("interrompido");
                }
            }
            if (primeiraOperacao > 100) {
                // Último lote: falha na hora
                throw DynamoDbException.builder().statusCode(500).message("falha").build();
            }
            return DynamoDbFalso.tudoEncontrado(requisicao);
        });
        var adapter = adapter(8);

        var inicio = System.nanoTime();
        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS)))
                .isExactlyInstanceOf(FalhaNaFonteDeOperacoesException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(5));
        assertThat(lentoInterrompido).isTrue();
        // O lote interrompido não conta no circuito; só a falha real
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void unprocessedKeysQueSobramDepoisDasReleiturasViram503EContamUmaFalhaNoCircuito() {
        dynamoDb.responderBatch(requisicao -> BatchGetItemResponse.builder()
                .responses(Map.of())
                .unprocessedKeys(requisicao.requestItems())
                .build());
        var adapter = adapter(4);

        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO)))
                .isInstanceOf(VisaoConsolidadaSobrecarregadaException.class)
                .satisfies(e -> assertThat(((VisaoConsolidadaSobrecarregadaException) e).tenteNovamenteEm())
                        .isEqualTo(Duration.ofSeconds(1)));
        assertThat(dynamoDb.chamadasBatch()).isEqualTo(3);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
    }

    @Test
    void itemDoIndiceSemTextoDetalheVira502SemContarNoCircuito() {
        dynamoDb.responderQuery(requisicao -> QueryResponse.builder()
                .items(Map.of(VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN("1")))
                .build());
        var adapter = adapter(4);

        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO)))
                .isInstanceOf(VisaoConsolidadaInvalidaException.class)
                .hasMessageContaining(VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void textoDetalheCorrompidoVira502() {
        dynamoDb.responderBatch(requisicao -> {
            var itens = new ArrayList<Map<String, AttributeValue>>();
            for (var chave : chaves(requisicao)) {
                var item = new HashMap<>(chave);
                item.put(VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE,
                        AttributeValue.fromB(SdkBytes.fromUtf8String("não é gzip")));
                itens.add(item);
            }
            return BatchGetItemResponse.builder().responses(Map.of(tabela(requisicao), itens)).build();
        });
        var adapter = adapter(4);

        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO)))
                .isInstanceOf(VisaoConsolidadaInvalidaException.class)
                .isInstanceOf(FalhaNaFonteDeOperacoesException.class);
    }

    @Test
    void itemDaTabelaComBlocoDesconhecidoVira502() {
        dynamoDb.responderBatch(requisicao -> BatchGetItemResponse.builder()
                .responses(Map.of(tabela(requisicao), List.of(Map.of(
                        VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN("1"),
                        VisaoConsolidadaDynamoDbAdapter.SK, AttributeValue.fromS("SF#EXPAND#DESCONHECIDO"),
                        VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE, AttributeValue.fromB(
                                SdkBytes.fromByteArray(JSON_VAZIO))))))
                .build());
        var adapter = adapter(4);

        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO)))
                .isInstanceOf(VisaoConsolidadaInvalidaException.class);
    }

    @Test
    void operacaoEncontradaDevolveOsBlocos() {
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).hasSize(1);
        assertThat(operacoes.getFirst().blocos()).containsKeys(Bloco.OPERACAO, Bloco.SALDO);
        assertThat(operacoes.getFirst().blocosIndisponiveis()).isEmpty();
    }

    private VisaoConsolidadaDynamoDbAdapter adapter(int maxLotesSimultaneos) {
        var properties = new DynamoDbProperties("operacoes-credito", "sa-east-1", null, null, "gsi-cliente",
                "gsi-conta", Duration.ofSeconds(1), Duration.ofMillis(100), maxLotesSimultaneos,
                new DynamoDbProperties.Pool(100, Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofSeconds(1),
                        Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new DynamoDbProperties.Retry(0, Duration.ofMillis(10), Duration.ofMillis(50)));
        var resiliencia = new ResilienciaDynamoDb(circuitBreaker, bulkhead("dynamodb"),
                bulkhead("dynamodb-cliente-conta"), RateLimiter.of("dynamodb", RateLimiterConfig.custom()
                .limitForPeriod(10_000).timeoutDuration(Duration.ZERO).build()), Duration.ofSeconds(1));
        return new VisaoConsolidadaDynamoDbAdapter(dynamoDb, properties, ObservationRegistry.NOOP, new SemCache(),
                resiliencia);
    }

    private static Bulkhead bulkhead(String nome) {
        return Bulkhead.of(nome, BulkheadConfig.custom().maxConcurrentCalls(100).maxWaitDuration(Duration.ZERO).build());
    }

    private static CircuitBreakerConfig circuito() {
        var circuito = CircuitBreakerConfig.custom().slidingWindowSize(100).minimumNumberOfCalls(100);
        ResilienciaDynamoDb.classificarFalhas(circuito);
        return circuito.build();
    }

    private static List<Map<String, AttributeValue>> chaves(BatchGetItemRequest requisicao) {
        return requisicao.requestItems().values().iterator().next().keys();
    }

    private static String tabela(BatchGetItemRequest requisicao) {
        return requisicao.requestItems().keySet().iterator().next();
    }

    private static byte[] gzip(String json) {
        var saida = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(saida)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saida.toByteArray();
    }

    private static final class SemCache implements CacheDeBlocos {

        @Override
        public Map<ChaveDeBloco, byte[]> buscar(Collection<ChaveDeBloco> chaves) {
            return Map.of();
        }

        @Override
        public void gravar(Map<ChaveDeBloco, byte[]> blocos) {
            // sem cache
        }
    }

    /** DynamoDB em memória: todo item pedido existe (com {@code texto_detalhe} = {@code {}}), salvo outra resposta. */
    private static final class DynamoDbFalso implements DynamoDbClient {

        private volatile Function<QueryRequest, QueryResponse> query = requisicao -> QueryResponse.builder().build();
        private volatile Function<BatchGetItemRequest, BatchGetItemResponse> batch = DynamoDbFalso::tudoEncontrado;
        private volatile Duration demoraDoBatch = Duration.ZERO;
        private final AtomicInteger chamadasBatch = new AtomicInteger();
        private final AtomicInteger emAndamento = new AtomicInteger();
        private final AtomicInteger maximoEmAndamento = new AtomicInteger();

        void operacoesNoIndice(int quantidade) {
            query = requisicao -> {
                var itens = new ArrayList<Map<String, AttributeValue>>();
                for (long id = 1; id <= quantidade; id++) {
                    itens.add(Map.of(VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN(Long.toString(id)),
                            VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE,
                            AttributeValue.fromB(SdkBytes.fromByteArray(JSON_VAZIO))));
                }
                return QueryResponse.builder().items(itens).build();
            };
        }

        void responderQuery(Function<QueryRequest, QueryResponse> resposta) {
            this.query = resposta;
        }

        void responderBatch(Function<BatchGetItemRequest, BatchGetItemResponse> resposta) {
            this.batch = resposta;
        }

        void demoraDoBatch(Duration demora) {
            this.demoraDoBatch = demora;
        }

        int chamadasBatch() {
            return chamadasBatch.get();
        }

        int maximoEmAndamento() {
            return maximoEmAndamento.get();
        }

        @Override
        public QueryResponse query(QueryRequest requisicao) {
            return query.apply(requisicao);
        }

        @Override
        public BatchGetItemResponse batchGetItem(BatchGetItemRequest requisicao) {
            chamadasBatch.incrementAndGet();
            maximoEmAndamento.accumulateAndGet(emAndamento.incrementAndGet(), Math::max);
            try {
                if (!demoraDoBatch.isZero()) {
                    Thread.sleep(demoraDoBatch);
                }
                return batch.apply(requisicao);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AbortedException.create("interrompido");
            } finally {
                emAndamento.decrementAndGet();
            }
        }

        static BatchGetItemResponse tudoEncontrado(BatchGetItemRequest requisicao) {
            var itens = new ArrayList<Map<String, AttributeValue>>();
            for (var chave : chaves(requisicao)) {
                var item = new HashMap<>(chave);
                item.put(VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE,
                        AttributeValue.fromB(SdkBytes.fromByteArray(JSON_VAZIO)));
                itens.add(item);
            }
            return BatchGetItemResponse.builder().responses(Map.of(tabela(requisicao), itens)).build();
        }

        @Override
        public String serviceName() {
            return SERVICE_NAME;
        }

        @Override
        public void close() {
            // nada a fechar
        }
    }
}
