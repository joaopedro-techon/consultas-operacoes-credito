package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.BlocoVersionado;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeCustodia;
import br.com.consultas.operacoescredito.adapters.out.cache.ChaveDeOperacao;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.VisaoConsolidadaProperties;
import br.com.consultas.operacoescredito.config.observabilidade.MetricasDeCustodia;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import br.com.consultas.operacoescredito.domain.VersaoDosBlocos;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter da visão consolidada contra um DynamoDB em memória que responde, atrasa ou falha como o teste mandar:
 * teto de lotes simultâneos por requisição, falha rápida de um lote qualquer, {@code UnprocessedKeys} esgotadas
 * e itens fora do formato; e a versão única por operação (ADR-0018): blocos em versões diferentes relidos com
 * {@code ConsistentRead} e, se seguem divergentes, 503. O cache é um mapa em memória.
 */
class VisaoConsolidadaDynamoDbAdapterTest {

    private static final String CLIENTE = "52998224725";
    private static final byte[] JSON_VAZIO = gzip("{}");
    private static final String VERSAO = "2026-11-25T10:00:00.000Z";
    private static final String VERSAO_ANTERIOR = "2026-11-25T09:00:00.000Z";
    private static final long VERSAO_EPOCH = Instant.parse(VERSAO).toEpochMilli();
    private static final long EPOCH_ANTERIOR = Instant.parse(VERSAO_ANTERIOR).toEpochMilli();

    private final DynamoDbFalso dynamoDb = new DynamoDbFalso();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("dynamodb", circuito());
    private final CacheEmMemoria cache = new CacheEmMemoria();
    private final SimpleMeterRegistry metricas = new SimpleMeterRegistry();

    @Test
    void blocosEmVersoesDiferentesSaoRelidosComConsistentRead() {
        // Leitura eventual atrasada: o saldo ainda na versão anterior; a consistente já vê tudo na nova
        dynamoDb.versoes((item, consistente) -> !consistente && sk(item).endsWith("#SALDO") ? VERSAO_ANTERIOR : VERSAO);
        cache.lembrar(1, Custodia.SF);
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.blocos()).containsKeys(Bloco.OPERACAO, Bloco.SALDO));
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false, true);
        assertThat(cache.gravacoes).singleElement().satisfies(gravacao -> {
            assertThat(gravacao.versaoUnica()).hasValue(VERSAO_EPOCH);
            assertThat(gravacao.blocos()).containsOnlyKeys(Bloco.OPERACAO, Bloco.SALDO);
        });
        assertThat(metricas.get(VisaoConsolidadaDynamoDbAdapter.METRICA_VERSAO_DIVERGENTE)
                .tag("resultado", "relida").counter().count()).isEqualTo(1);
    }

    @Test
    void versoesQueSeguemDivergentesDepoisDasReleiturasViram503SemContarNoCircuito() {
        dynamoDb.versoes((item, consistente) -> sk(item).endsWith("#SALDO") ? VERSAO_ANTERIOR : VERSAO);
        var adapter = adapter(4);

        assertThatThrownBy(() -> adapter.buscar(new CriterioConsulta.PorOperacao(1),
                EnumSet.of(Bloco.OPERACAO, Bloco.SALDO)))
                .isInstanceOf(VersaoDivergenteException.class)
                .satisfies(e -> assertThat(((VersaoDivergenteException) e).tenteNovamenteEm())
                        .isEqualTo(Duration.ofSeconds(1)));
        // Descoberta (eventual) e duas releituras consistentes
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false, true, true);
        assertThat(cache.gravacoes).isEmpty();
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void blocoDoCacheDeVersaoAnteriorAoDoDynamoDbNaoEMisturado() {
        // O cache tem o operacao da versão anterior; o saldo, lido agora, já está na nova
        cache.lembrar(1, Custodia.SF);
        cache.guardar(new ChaveDeOperacao(1, Custodia.SF), Instant.parse(VERSAO_ANTERIOR).toEpochMilli(),
                Map.of(Bloco.OPERACAO, gzip("{\"versao\":\"anterior\"}")));
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.blocos().get(Bloco.OPERACAO)).isNotEqualTo(Map.of("versao", "anterior")));
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false, true);
        assertThat(dynamoDb.chavesConsistentes()).containsExactlyInAnyOrder("1|SF#EXPAND#OPERACAO",
                "1|SF#EXPAND#SALDO");
    }

    @Test
    void itensSemVersaoSaoEntreguesMasNaoVaoAoCache() {
        dynamoDb.versoes((item, consistente) -> null);
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).hasSize(1);
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false);
        assertThat(cache.gravacoes).isEmpty();
        assertThat(cache.custodia(1)).isEmpty();
    }

    @Test
    void operacaoDoIndiceAtrasadaEmRelacaoATabelaBaseERelidaInteira() {
        dynamoDb.operacoesNoIndice(2);
        // O GSI replica depois da tabela base: o bloco operacao da operação 2 ainda está na versão anterior
        dynamoDb.versoesDoIndice(item ->
                "2".equals(item.get(VisaoConsolidadaDynamoDbAdapter.PK).n()) ? VERSAO_ANTERIOR : VERSAO);
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS));

        assertThat(operacoes).hasSize(2);
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false, true);
        assertThat(dynamoDb.chavesConsistentes()).as("só a operação divergente, com o bloco operacao")
                .containsExactlyInAnyOrder("2|SF#EXPAND#OPERACAO", "2|SF#EXPAND#PARCELAS");
        // Os blocos relidos vão ao cache sem o operacao, que vem do GSI
        assertThat(cache.gravacoes).allSatisfy(gravacao ->
                assertThat(gravacao.blocos()).containsOnlyKeys(Bloco.PARCELAS));
    }

    @Test
    void clienteUsaDoCacheSoOsBlocosDaVersaoDoIndice() {
        dynamoDb.operacoesNoIndice(2);
        cache.guardar(new ChaveDeOperacao(1, Custodia.SF), VERSAO_EPOCH, Map.of(Bloco.PARCELAS, JSON_VAZIO));
        cache.guardar(new ChaveDeOperacao(2, Custodia.SF), VERSAO_EPOCH - 1, Map.of(Bloco.PARCELAS, JSON_VAZIO));
        var adapter = adapter(4);

        adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS));

        assertThat(dynamoDb.chavesLidas()).containsExactly("2|SF#EXPAND#PARCELAS");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Versão por bloco (ADR-0019)
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void comVersaoPorBlocoBlocosEmVersoesDiferentesSaoEntreguesSemReleitura() {
        // Pagamento de parcela: o saldo foi regravado, a operacao não
        dynamoDb.versoes((item, consistente) -> sk(item).endsWith("#SALDO") ? VERSAO : VERSAO_ANTERIOR);
        var adapter = adapter(4, VersaoDosBlocos.POR_BLOCO);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.blocos()).containsKeys(Bloco.OPERACAO, Bloco.SALDO));
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false);
        assertThat(cache.gravacoes).singleElement().satisfies(gravacao -> {
            assertThat(gravacao.blocos().get(Bloco.SALDO).versao()).isEqualTo(VERSAO_EPOCH);
            assertThat(gravacao.blocos().get(Bloco.OPERACAO).versao()).isEqualTo(EPOCH_ANTERIOR);
        });
        assertThat(cache.custodias.get(1L).versao()).as("custódia na versão do bloco operacao")
                .isEqualTo(EPOCH_ANTERIOR);
        assertThat(metricas.find(VisaoConsolidadaDynamoDbAdapter.METRICA_VERSAO_DIVERGENTE)
                .tag("resultado", "relida").counter().count()).isZero();
    }

    @Test
    void comVersaoPorBlocoBlocoDoCacheDeOutraVersaoEUsadoSemReler() {
        cache.lembrar(1, Custodia.SF);
        cache.guardar(new ChaveDeOperacao(1, Custodia.SF), EPOCH_ANTERIOR, Map.of(Bloco.ORIGINACAO, JSON_VAZIO));
        var adapter = adapter(4, VersaoDosBlocos.POR_BLOCO);

        adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.ORIGINACAO));

        assertThat(dynamoDb.chavesLidas()).containsExactly("1|SF#EXPAND#OPERACAO");
        assertThat(dynamoDb.leiturasConsistentes()).containsExactly(false);
    }

    @Test
    void comVersaoPorBlocoClienteUsaDoCacheOsBlocosEmQualquerVersao() {
        dynamoDb.operacoesNoIndice(2);
        cache.guardar(new ChaveDeOperacao(1, Custodia.SF), EPOCH_ANTERIOR, Map.of(Bloco.PARCELAS, JSON_VAZIO));
        // Operação 2: o GSI sem versão; com versão por bloco, o cache é consultado mesmo assim
        dynamoDb.versoesDoIndice(item -> "2".equals(item.get(VisaoConsolidadaDynamoDbAdapter.PK).n()) ? null : VERSAO);
        cache.guardar(new ChaveDeOperacao(2, Custodia.SF), VERSAO_EPOCH, Map.of(Bloco.PARCELAS, JSON_VAZIO));
        var adapter = adapter(4, VersaoDosBlocos.POR_BLOCO);

        var operacoes = adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.empty()),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS));

        assertThat(operacoes).hasSize(2);
        assertThat(dynamoDb.chamadasBatch()).isZero();
    }

    @Test
    void comVersaoPorBlocoSoOBlocoSemVersaoDeixaDeIrAoCache() {
        dynamoDb.versoes((item, consistente) -> sk(item).endsWith("#SALDO") ? null : VERSAO);
        var adapter = adapter(4, VersaoDosBlocos.POR_BLOCO);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.blocos()).containsKeys(Bloco.OPERACAO, Bloco.SALDO));
        assertThat(cache.gravacoes).singleElement().satisfies(gravacao ->
                assertThat(gravacao.blocos()).containsOnlyKeys(Bloco.OPERACAO));
        // Todas as custódias têm a operação no DynamoDB falso: vence a NSF
        assertThat(cache.custodia(1)).contains(Custodia.NSF);
    }

    @Test
    void comVersaoPorBlocoOperacaoSemVersaoNaoGravaACustodiaMasGravaOsDemaisBlocos() {
        dynamoDb.versoes((item, consistente) -> sk(item).endsWith("#OPERACAO") ? null : VERSAO);
        var adapter = adapter(4, VersaoDosBlocos.POR_BLOCO);

        adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(cache.custodia(1)).isEmpty();
        assertThat(cache.gravacoes).singleElement().satisfies(gravacao ->
                assertThat(gravacao.blocos()).containsOnlyKeys(Bloco.SALDO));
    }

    private static String sk(Map<String, AttributeValue> item) {
        return item.get(VisaoConsolidadaDynamoDbAdapter.SK).s();
    }

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
                .items(Map.of(VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN("1"),
                        VisaoConsolidadaDynamoDbAdapter.SK, AttributeValue.fromS("SF#EXPAND#OPERACAO")))
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

    @Test
    void semCustodiaEmCacheProcuraNasQuatroCustodiasEFicaComADeMaiorPrecedencia() {
        dynamoDb.custodiasComItens(Set.of(Custodia.SF, Custodia.NSF));
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao -> {
            assertThat(operacao.custodia()).contains(Custodia.NSF);
            assertThat(operacao.blocosIndisponiveis()).isEmpty();
        });
        // 4 custódias × 2 blocos num só BatchGetItem
        assertThat(dynamoDb.chamadasBatch()).isEqualTo(1);
        assertThat(dynamoDb.custodiasPedidas()).containsExactlyInAnyOrder(Custodia.values());
        assertThat(cache.custodia(1)).contains(Custodia.NSF);
        assertThat(metricas.get(MetricasDeCustodia.DUPLICADA).counter().count()).isEqualTo(1);
    }

    @Test
    void comCustodiaEmCachePedeSoNela() {
        cache.lembrar(1, Custodia.F5);
        dynamoDb.custodiasComItens(Set.of(Custodia.F5));
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO, Bloco.SALDO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.custodia()).contains(Custodia.F5));
        assertThat(dynamoDb.custodiasPedidas()).containsExactly(Custodia.F5);
    }

    @Test
    void custodiaDoCacheSemAOperacaoRefazADescoberta() {
        cache.lembrar(1, Custodia.F5);
        dynamoDb.custodiasComItens(Set.of(Custodia.OD));
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO));

        assertThat(operacoes).singleElement().satisfies(operacao ->
                assertThat(operacao.custodia()).contains(Custodia.OD));
        assertThat(dynamoDb.chamadasBatch()).isEqualTo(2);
        assertThat(cache.custodia(1)).contains(Custodia.OD);
    }

    @Test
    void operacaoInexistenteEmTodasAsCustodiasEsqueceACustodiaLembrada() {
        cache.lembrar(1, Custodia.F5);
        dynamoDb.custodiasComItens(Set.of());
        var adapter = adapter(4);

        assertThat(adapter.buscar(new CriterioConsulta.PorOperacao(1), EnumSet.of(Bloco.OPERACAO))).isEmpty();
        assertThat(cache.custodia(1)).isEmpty();
        assertThat(cache.remocoes.get()).isEqualTo(1);
    }

    @Test
    void operacaoRepetidaNoIndiceFicaComACustodiaDeMaiorPrecedencia() {
        dynamoDb.responderQuery(requisicao -> QueryResponse.builder()
                .items(itemDoIndice(1, Custodia.SF), itemDoIndice(1, Custodia.NSF), itemDoIndice(2, Custodia.OD))
                .build());
        var adapter = adapter(4);

        var operacoes = adapter.buscar(new CriterioConsulta.PorCliente(CLIENTE, OptionalInt.of(2)),
                EnumSet.of(Bloco.OPERACAO, Bloco.PARCELAS));

        assertThat(operacoes).extracting(operacao -> operacao.custodia().orElseThrow())
                .containsExactly(Custodia.NSF, Custodia.OD);
        assertThat(dynamoDb.custodiasPedidas()).containsExactlyInAnyOrder(Custodia.NSF, Custodia.OD);
    }

    private static Map<String, AttributeValue> itemDoIndice(long id, Custodia custodia) {
        return Map.of(VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN(Long.toString(id)),
                VisaoConsolidadaDynamoDbAdapter.SK, AttributeValue.fromS(custodia.codigo() + "#EXPAND#OPERACAO"),
                VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE, AttributeValue.fromB(SdkBytes.fromByteArray(JSON_VAZIO)));
    }

    private VisaoConsolidadaDynamoDbAdapter adapter(int maxLotesSimultaneos) {
        return adapter(maxLotesSimultaneos, VersaoDosBlocos.UNICA);
    }

    private VisaoConsolidadaDynamoDbAdapter adapter(int maxLotesSimultaneos, VersaoDosBlocos versaoDosBlocos) {
        var properties = new DynamoDbProperties("operacoes-credito", "sa-east-1", null, null, "gsi-cliente",
                "gsi-conta", Duration.ofSeconds(1), Duration.ofMillis(100), maxLotesSimultaneos,
                new DynamoDbProperties.Pool(100, Duration.ofMillis(100), Duration.ofMillis(20), Duration.ofSeconds(1),
                        Duration.ofMinutes(5), Duration.ofSeconds(30)),
                new DynamoDbProperties.Retry(0, Duration.ofMillis(10), Duration.ofMillis(50)));
        var resiliencia = new ResilienciaDynamoDb(circuitBreaker, bulkhead("dynamodb"),
                bulkhead("dynamodb-cliente-conta"), RateLimiter.of("dynamodb", RateLimiterConfig.custom()
                .limitForPeriod(10_000).timeoutDuration(Duration.ZERO).build()), Duration.ofSeconds(1));
        return new VisaoConsolidadaDynamoDbAdapter(dynamoDb, properties, ObservationRegistry.NOOP, cache, cache,
                resiliencia, PrecedenciaDeCustodia.PADRAO, new VisaoConsolidadaProperties(versaoDosBlocos), metricas);
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

    /**
     * Cache em memória: as custódias (com versão) e os blocos (com versão, sem aplicar a regra do script) que o
     * adapter grava, para ver o que ele lembra. Blocos guardados aqui voltam na leitura.
     */
    private static final class CacheEmMemoria implements CacheDeBlocos, CacheDeCustodia {

        private final Map<Long, CustodiaLembrada> custodias = new ConcurrentHashMap<>();
        private final Map<ChaveDeOperacao, BlocosEmCache> blocos = new ConcurrentHashMap<>();
        private final List<GravacaoDeBlocos> gravacoes = new CopyOnWriteArrayList<>();
        private final AtomicInteger remocoes = new AtomicInteger();

        void lembrar(long idOperacao, Custodia custodia) {
            custodias.put(idOperacao, new CustodiaLembrada(custodia, VERSAO_EPOCH));
        }

        void guardar(ChaveDeOperacao chave, long versao, Map<Bloco, byte[]> blocosDoHash) {
            blocos.put(chave, BlocosEmCache.naVersao(versao, blocosDoHash));
        }

        Optional<Custodia> custodia(long idOperacao) {
            return Optional.ofNullable(custodias.get(idOperacao)).map(CustodiaLembrada::custodia);
        }

        @Override
        public LeituraDaOperacao buscarOperacao(long idOperacao, Set<Bloco> pedidos) {
            var lembrada = Optional.ofNullable(custodias.get(idOperacao));
            var doHash = lembrada.map(custodia -> blocos.get(new ChaveDeOperacao(idOperacao, custodia.custodia())))
                    .map(hash -> filtrar(hash, pedidos));
            return new LeituraDaOperacao(lembrada, doHash);
        }

        @Override
        public Map<ChaveDeOperacao, BlocosEmCache> buscar(Map<ChaveDeOperacao, OptionalLong> versoesExigidas,
                                                          Set<Bloco> pedidos) {
            var encontrados = new HashMap<ChaveDeOperacao, BlocosEmCache>();
            versoesExigidas.forEach((chave, exigida) -> {
                var hash = blocos.get(chave);
                if (hash != null) {
                    var filtrado = filtrar(hash, pedidos, exigida);
                    if (!filtrado.blocos().isEmpty()) {
                        encontrados.put(chave, filtrado);
                    }
                }
            });
            return encontrados;
        }

        private static BlocosEmCache filtrar(BlocosEmCache hash, Set<Bloco> pedidos) {
            return filtrar(hash, pedidos, OptionalLong.empty());
        }

        private static BlocosEmCache filtrar(BlocosEmCache hash, Set<Bloco> pedidos, OptionalLong exigida) {
            var filtrados = new HashMap<Bloco, BlocoVersionado>();
            hash.blocos().forEach((bloco, versionado) -> {
                if (pedidos.contains(bloco)
                        && (exigida.isEmpty() || exigida.getAsLong() == versionado.versao())) {
                    filtrados.put(bloco, versionado);
                }
            });
            return new BlocosEmCache(filtrados);
        }

        @Override
        public void gravar(Collection<GravacaoDeBlocos> blocos, Collection<GravacaoDeCustodia> custodias) {
            gravacoes.addAll(blocos);
            custodias.forEach(custodia -> this.custodias.put(custodia.idOperacao(),
                    new CustodiaLembrada(custodia.custodia(), custodia.versao())));
        }

        @Override
        public void remover(long idOperacao) {
            remocoes.incrementAndGet();
            custodias.remove(idOperacao);
        }
    }

    /**
     * DynamoDB em memória: todo item pedido existe (com {@code texto_detalhe} = {@code {}}), salvo outra resposta.
     * Item sem {@code codigo_identificador_carga} na resposta ganha o de {@link #versoes}, que sabe se a leitura é
     * consistente ({@code null}: o item fica sem versão).
     */
    private static final class DynamoDbFalso implements DynamoDbClient {

        private volatile Function<QueryRequest, QueryResponse> query = requisicao -> QueryResponse.builder().build();
        private volatile Function<BatchGetItemRequest, BatchGetItemResponse> batch = DynamoDbFalso::tudoEncontrado;
        private volatile BiFunction<Map<String, AttributeValue>, Boolean, String> versoes = (item, consistente) ->
                VERSAO;
        private volatile Function<Map<String, AttributeValue>, String> versoesDoIndice = item -> VERSAO;
        private volatile Duration demoraDoBatch = Duration.ZERO;
        private final Set<Custodia> custodiasPedidas = ConcurrentHashMap.newKeySet();
        private final List<Boolean> leiturasConsistentes = new CopyOnWriteArrayList<>();
        private final List<String> chavesLidas = new CopyOnWriteArrayList<>();
        private final List<String> chavesConsistentes = new CopyOnWriteArrayList<>();
        private final AtomicInteger chamadasBatch = new AtomicInteger();
        private final AtomicInteger emAndamento = new AtomicInteger();
        private final AtomicInteger maximoEmAndamento = new AtomicInteger();

        void operacoesNoIndice(int quantidade) {
            query = requisicao -> {
                var itens = new ArrayList<Map<String, AttributeValue>>();
                for (long id = 1; id <= quantidade; id++) {
                    itens.add(Map.of(VisaoConsolidadaDynamoDbAdapter.PK, AttributeValue.fromN(Long.toString(id)),
                            VisaoConsolidadaDynamoDbAdapter.SK, AttributeValue.fromS("SF#EXPAND#OPERACAO"),
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

        void versoes(BiFunction<Map<String, AttributeValue>, Boolean, String> versoes) {
            this.versoes = versoes;
        }

        void versoesDoIndice(Function<Map<String, AttributeValue>, String> versoes) {
            this.versoesDoIndice = versoes;
        }

        /** {@code ConsistentRead} de cada {@code BatchGetItem}, na ordem. */
        List<Boolean> leiturasConsistentes() {
            return leiturasConsistentes;
        }

        /** Chaves ({@code id|sk}) pedidas nos {@code BatchGetItem}s. */
        List<String> chavesLidas() {
            return chavesLidas;
        }

        /** Chaves ({@code id|sk}) pedidas nos {@code BatchGetItem}s com {@code ConsistentRead}. */
        List<String> chavesConsistentes() {
            return chavesConsistentes;
        }

        private List<Map<String, AttributeValue>> versionar(List<Map<String, AttributeValue>> itens,
                                                            Function<Map<String, AttributeValue>, String> versoes) {
            var versionados = new ArrayList<Map<String, AttributeValue>>(itens.size());
            for (var item : itens) {
                var versao = item.containsKey(VisaoConsolidadaDynamoDbAdapter.VERSAO)
                        ? null
                        : versoes.apply(item);
                if (versao == null) {
                    versionados.add(item);
                } else {
                    var comVersao = new HashMap<>(item);
                    comVersao.put(VisaoConsolidadaDynamoDbAdapter.VERSAO, AttributeValue.fromS(versao));
                    versionados.add(comVersao);
                }
            }
            return versionados;
        }

        /** Só as custódias informadas têm itens (todos os blocos pedidos). */
        void custodiasComItens(Set<Custodia> custodias) {
            batch = requisicao -> {
                var itens = new ArrayList<Map<String, AttributeValue>>();
                for (var chave : chaves(requisicao)) {
                    var sk = chave.get(VisaoConsolidadaDynamoDbAdapter.SK).s();
                    if (custodias.stream().anyMatch(custodia -> sk.startsWith(custodia.codigo() + "#"))) {
                        var item = new HashMap<>(chave);
                        item.put(VisaoConsolidadaDynamoDbAdapter.TEXTO_DETALHE,
                                AttributeValue.fromB(SdkBytes.fromByteArray(JSON_VAZIO)));
                        itens.add(item);
                    }
                }
                return BatchGetItemResponse.builder().responses(Map.of(tabela(requisicao), itens)).build();
            };
        }

        Set<Custodia> custodiasPedidas() {
            return custodiasPedidas;
        }

        int chamadasBatch() {
            return chamadasBatch.get();
        }

        int maximoEmAndamento() {
            return maximoEmAndamento.get();
        }

        @Override
        public QueryResponse query(QueryRequest requisicao) {
            var resposta = query.apply(requisicao);
            return resposta.toBuilder().items(versionar(resposta.items(), versoesDoIndice)).build();
        }

        @Override
        public BatchGetItemResponse batchGetItem(BatchGetItemRequest requisicao) {
            chamadasBatch.incrementAndGet();
            var consistente = Boolean.TRUE.equals(
                    requisicao.requestItems().values().iterator().next().consistentRead());
            leiturasConsistentes.add(consistente);
            for (var chave : chaves(requisicao)) {
                var lida = chave.get(VisaoConsolidadaDynamoDbAdapter.PK).n() + "|"
                        + chave.get(VisaoConsolidadaDynamoDbAdapter.SK).s();
                chavesLidas.add(lida);
                if (consistente) {
                    chavesConsistentes.add(lida);
                }
            }
            for (var chave : chaves(requisicao)) {
                var sk = chave.get(VisaoConsolidadaDynamoDbAdapter.SK).s();
                Custodia.porCodigo(sk.substring(0, sk.indexOf('#'))).ifPresent(custodiasPedidas::add);
            }
            maximoEmAndamento.accumulateAndGet(emAndamento.incrementAndGet(), Math::max);
            try {
                if (!demoraDoBatch.isZero()) {
                    Thread.sleep(demoraDoBatch);
                }
                var resposta = batch.apply(requisicao);
                var versionadas = new HashMap<String, List<Map<String, AttributeValue>>>();
                resposta.responses().forEach((tabela, itens) -> versionadas.put(tabela,
                        versionar(itens, item -> versoes.apply(item, consistente))));
                return resposta.toBuilder().responses(versionadas).build();
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
