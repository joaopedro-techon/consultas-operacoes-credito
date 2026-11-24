package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;
import br.com.consultas.operacoescredito.config.RotaAtiva;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeCustodia;
import br.com.consultas.operacoescredito.adapters.out.cache.ChaveDeBloco;
import br.com.consultas.operacoescredito.config.observabilidade.MetricasDeCustodia;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Visão consolidada no DynamoDB (ADR-0001): um item por operação, custódia e bloco,
 * PK {@code numero_operacao}, SK {@code <CUSTODIA>#EXPAND#<BLOCO>}, payload GZIP em {@code texto_detalhe}.
 * <p>
 * Descoberta de custódia (ADR-0012):
 * <ul>
 *     <li>OPERACAO: a custódia vem do cache de custódia, no mesmo {@code MGET} dos blocos. Conhecida, o
 *     {@code BatchGetItem} pede só os blocos ausentes do cache nela; se ela não tem mais a operação, ou se não
 *     estava em cache, os blocos são pedidos nas quatro custódias (até 36 chaves, um lote) e vale a de maior
 *     {@link PrecedenciaDeCustodia precedência} entre as que têm o bloco {@code operacao};</li>
 *     <li>CLIENTE e CONTA: {@code Query} no GSI esparso (mais recentes primeiro), que já traz o bloco
 *     {@code operacao} e a SK (logo, a custódia) de cada operação; operação repetida em mais de uma custódia fica
 *     com a de maior precedência. Depois, {@code BatchGetItem} dos demais blocos na custódia de cada operação, em
 *     lotes paralelos de até 100 chaves.</li>
 * </ul>
 * Todos os blocos de uma operação vêm da mesma custódia, e ela volta no campo {@code custodia} do bloco
 * {@code operacao}.
 * <p>
 * Os blocos lidos da tabela base passam pelo {@link CacheDeBlocos} (ADR-0002, ADR-0011): leitura de todas as chaves
 * operação × bloco, {@code BatchGetItem} só dos ausentes e gravação deles (e da custódia) no cache, fora da
 * requisição. A {@code Query} no GSI nunca é cacheada.
 * <p>
 * Resiliência ({@link ResilienciaDynamoDb}): a consulta passa pelos bulkheads do tipo de consulta e pelo rate
 * limiter; cada chamada ao SDK, pelo circuit breaker, com {@code apiCallTimeout} limitado ao que resta do
 * {@link PrazoDaRequisicao prazo da requisição}. Os lotes paralelos de uma requisição são no máximo
 * {@code consultas.dynamodb.max-lotes-simultaneos} ao mesmo tempo; a espera por vaga, a espera pelos lotes e a
 * releitura das {@code UnprocessedKeys} também respeitam o prazo: estourado, 504. Item fora do formato
 * ({@link VisaoConsolidadaInvalidaException}): 502.
 */
@Component
@RotaAtiva(RotaAtiva.Rota.VISAO_CONSOLIDADA)
class VisaoConsolidadaDynamoDbAdapter implements FonteDeOperacoes {

    static final String PK = "numero_operacao";
    static final String SK = "custodia_expand";
    static final String TEXTO_DETALHE = "texto_detalhe";
    static final String ATRIBUTO_CLIENTE = "cpf_cnpj_cliente";
    static final String ATRIBUTO_CONTA = "conta_cliente";
    /** Separador entre a custódia e o bloco na SK: {@code <CUSTODIA>#EXPAND#<BLOCO>}. */
    static final String INFIXO_SK = "#EXPAND#";
    /** Valor da tag {@code rota} das métricas de custódia. */
    static final String ROTA = "visao_consolidada";
    private static final String FONTE = "visão consolidada";
    /** Limite de chaves por {@code BatchGetItem} imposto pelo DynamoDB. */
    private static final int CHAVES_POR_LOTE = 100;
    private static final int MAX_LEITURAS_BATCH = 3;
    private static final long BACKOFF_BASE_MS = 10;
    /** Prazo que precisa sobrar, além do backoff, para valer reler as {@code UnprocessedKeys}. */
    private static final Duration PRAZO_MINIMO_PARA_RELEITURA = Duration.ofMillis(10);
    private static final Set<Custodia> TODAS = EnumSet.allOf(Custodia.class);

    static final String ETAPA_CONSULTA_GSI = "consulta_gsi";
    static final String ETAPA_BATCH = "batch";

    private final DynamoDbClient dynamoDb;
    private final ObservationRegistry observationRegistry;
    private final CacheDeBlocos cache;
    private final CacheDeCustodia cacheDeCustodia;
    private final ResilienciaDynamoDb resiliencia;
    private final PrecedenciaDeCustodia precedencia;
    private final MetricasDeCustodia metricas;
    private final String tabela;
    private final String indiceCliente;
    private final String indiceConta;
    private final int maxLotesSimultaneos;
    private final TextoDetalheCodec codec = new TextoDetalheCodec();
    /** Leva MDC (e demais contextos registrados) às virtual threads dos lotes. */
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    VisaoConsolidadaDynamoDbAdapter(DynamoDbClient dynamoDb, DynamoDbProperties properties,
                                    ObservationRegistry observationRegistry, CacheDeBlocos cache,
                                    CacheDeCustodia cacheDeCustodia, ResilienciaDynamoDb resiliencia,
                                    PrecedenciaDeCustodia precedencia, MeterRegistry meterRegistry) {
        this.dynamoDb = dynamoDb;
        this.observationRegistry = observationRegistry;
        this.cache = cache;
        this.cacheDeCustodia = cacheDeCustodia;
        this.resiliencia = resiliencia;
        this.precedencia = precedencia;
        this.metricas = new MetricasDeCustodia(meterRegistry, ROTA);
        this.tabela = properties.tabela();
        this.indiceCliente = properties.indiceCliente();
        this.indiceConta = properties.indiceConta();
        this.maxLotesSimultaneos = properties.maxLotesSimultaneos();
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
        return resiliencia.consulta(criterio.tipo(), () -> buscarSemProtecao(criterio, blocos));
    }

    private List<OperacaoConsultada> buscarSemProtecao(CriterioConsulta criterio, Set<Bloco> blocos) {
        return switch (criterio) {
            case CriterioConsulta.PorOperacao porOperacao -> buscarPorOperacao(porOperacao.idOperacao(), blocos);
            case CriterioConsulta.PorCliente porCliente -> buscarPorIndice(
                    indiceCliente, ATRIBUTO_CLIENTE, porCliente.cpfCnpj(), porCliente.limiteOperacoes(), blocos);
            case CriterioConsulta.PorConta porConta -> buscarPorIndice(
                    indiceConta, ATRIBUTO_CONTA, porConta.conta(), porConta.limiteOperacoes(), blocos);
        };
    }

    /** Pela custódia do cache, se houver e ainda tiver a operação; senão, descoberta nas quatro custódias. */
    private List<OperacaoConsultada> buscarPorOperacao(long idOperacao, Set<Bloco> blocos) {
        var leitura = cache.buscar(chavesDeBloco(List.of(idOperacao), blocos), List.of(idOperacao));
        var lembrada = leitura.custodias().get(idOperacao);
        if (lembrada != null) {
            metricas.contar(lembrada, MetricasDeCustodia.Resultado.CACHE);
            var itens = new EnumMap<Bloco, byte[]>(Bloco.class);
            leitura.blocos().forEach((chave, textoDetalhe) -> itens.put(chave.bloco(), textoDetalhe));
            var ausentes = EnumSet.noneOf(Bloco.class);
            blocos.stream().filter(bloco -> !itens.containsKey(bloco)).forEach(ausentes::add);
            if (!ausentes.isEmpty()) {
                var lidos = daOperacao(lerDoDynamoDb(chaves(idOperacao, Set.of(lembrada), ausentes)), idOperacao)
                        .getOrDefault(lembrada, Map.of());
                itens.putAll(lidos);
                gravarNoCache(idOperacao, lembrada, lidos);
            }
            if (itens.containsKey(Bloco.OPERACAO)) {
                return List.of(montar(lembrada, blocos, itens));
            }
            // A operação não está mais na custódia lembrada (migrou ou deixou de existir): descoberta completa
        }
        return descobrir(idOperacao, blocos, lembrada != null);
    }

    private List<OperacaoConsultada> descobrir(long idOperacao, Set<Bloco> blocos, boolean haviaLembrada) {
        var porCustodia = daOperacao(lerDoDynamoDb(chaves(idOperacao, TODAS, blocos)), idOperacao);
        var encontradas = EnumSet.noneOf(Custodia.class);
        for (var custodia : Custodia.values()) {
            var achou = porCustodia.getOrDefault(custodia, Map.of()).containsKey(Bloco.OPERACAO);
            if (achou) {
                encontradas.add(custodia);
            }
            metricas.contar(custodia, achou
                    ? MetricasDeCustodia.Resultado.ENCONTRADA
                    : MetricasDeCustodia.Resultado.NAO_ENCONTRADA);
        }
        var vencedora = precedencia.vencedora(encontradas);
        if (vencedora.isEmpty()) {
            if (haviaLembrada) {
                cacheDeCustodia.remover(idOperacao);
            }
            return List.of();
        }
        if (encontradas.size() > 1) {
            metricas.duplicada(idOperacao, encontradas, vencedora.get());
        }
        var itens = porCustodia.get(vencedora.get());
        gravarNoCache(idOperacao, vencedora.get(), itens);
        return List.of(montar(vencedora.get(), blocos, itens));
    }

    private void gravarNoCache(long idOperacao, Custodia custodia, Map<Bloco, byte[]> itens) {
        var paraGravar = new HashMap<ChaveDeBloco, byte[]>();
        itens.forEach((bloco, textoDetalhe) -> paraGravar.put(new ChaveDeBloco(idOperacao, bloco), textoDetalhe));
        cache.gravar(paraGravar, Map.of(idOperacao, custodia));
    }

    private OperacaoConsultada montar(Custodia custodia, Set<Bloco> blocos, Map<Bloco, byte[]> itens) {
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        blocos.forEach(bloco -> conteudo.put(bloco, decodificar(itens.get(bloco))));
        return OperacaoConsultada.naCustodia(custodia, conteudo);
    }

    private List<OperacaoConsultada> buscarPorIndice(String indice, String atributo, String valor,
                                                     OptionalInt limite, Set<Bloco> blocos) {
        var operacoes = Observacoes.etapa(observationRegistry, ETAPA_CONSULTA_GSI)
                .lowCardinalityKeyValue("indice", indice)
                .observe(() -> consultarIndice(indice, atributo, valor, limite));
        if (operacoes.isEmpty()) {
            return List.of();
        }
        var demaisBlocos = EnumSet.noneOf(Bloco.class);
        demaisBlocos.addAll(blocos);
        demaisBlocos.remove(Bloco.OPERACAO);
        var itens = Observacoes.etapa(observationRegistry, ETAPA_BATCH)
                .highCardinalityKeyValue("operacoes", Integer.toString(operacoes.size()))
                .observe(() -> lerBlocosNasCustodias(operacoes, demaisBlocos));

        var resultado = new ArrayList<OperacaoConsultada>(operacoes.size());
        for (var operacao : operacoes) {
            var itensDaOperacao = new EnumMap<Bloco, byte[]>(Bloco.class);
            itensDaOperacao.putAll(itens.getOrDefault(operacao.idOperacao(), Map.of()));
            itensDaOperacao.put(Bloco.OPERACAO, operacao.textoDetalhe());
            resultado.add(montar(operacao.custodia(), blocos, itensDaOperacao));
        }
        return resultado;
    }

    /**
     * Query no GSI esparso, mais recentes primeiro. Pagina até o limite de operações distintas (ou até o fim, sem
     * limite): o DynamoDB pode devolver menos itens que o {@code Limit} quando a página passa de 1 MB, e uma
     * operação em duas custódias ocupa dois itens. As repetições ficam com a custódia de maior precedência.
     */
    private List<OperacaoDoIndice> consultarIndice(String indice, String atributo, String valor, OptionalInt limite) {
        var porId = new LinkedHashMap<Long, List<OperacaoDoIndice>>();
        Map<String, AttributeValue> inicio = null;
        do {
            var requisicao = QueryRequest.builder()
                    .tableName(tabela)
                    .indexName(indice)
                    .keyConditionExpression("#chave = :valor")
                    .expressionAttributeNames(Map.of("#chave", atributo))
                    .expressionAttributeValues(Map.of(":valor", AttributeValue.fromS(valor)))
                    .scanIndexForward(false)
                    .projectionExpression(PK + ", " + SK + ", " + TEXTO_DETALHE)
                    .exclusiveStartKey(inicio)
                    .overrideConfiguration(resiliencia.noPrazo());
            if (limite.isPresent()) {
                // Folga para ver as repetições (adjacentes no GSI) da última operação em outras custódias
                requisicao.limit(limite.getAsInt() - porId.size() + Custodia.values().length - 1);
            }
            var resposta = resiliencia.chamada("Query", () -> dynamoDb.query(requisicao.build()));
            for (var item : resposta.items()) {
                var idOperacao = numeroOperacao(item);
                var chave = custodiaEBloco(item, idOperacao);
                if (limite.isPresent() && !porId.containsKey(idOperacao) && porId.size() >= limite.getAsInt()) {
                    break;
                }
                porId.computeIfAbsent(idOperacao, id -> new ArrayList<>())
                        .add(new OperacaoDoIndice(idOperacao, chave.custodia(), textoDetalhe(item, idOperacao)));
            }
            inicio = resposta.hasLastEvaluatedKey() && !resposta.lastEvaluatedKey().isEmpty()
                    ? resposta.lastEvaluatedKey()
                    : null;
        } while (inicio != null && (limite.isEmpty() || porId.size() < limite.getAsInt()));

        var operacoes = new ArrayList<OperacaoDoIndice>(porId.size());
        porId.forEach((id, itens) -> operacoes.add(semRepeticao(id, itens)));
        return operacoes;
    }

    private OperacaoDoIndice semRepeticao(long idOperacao, List<OperacaoDoIndice> itens) {
        var porCustodia = new EnumMap<Custodia, OperacaoDoIndice>(Custodia.class);
        itens.forEach(item -> porCustodia.putIfAbsent(item.custodia(), item));
        var vencedora = precedencia.vencedora(porCustodia.keySet()).orElseThrow();
        if (porCustodia.size() > 1) {
            metricas.duplicada(idOperacao, porCustodia.keySet(), vencedora);
        }
        metricas.contar(vencedora, MetricasDeCustodia.Resultado.ENCONTRADA);
        return porCustodia.get(vencedora);
    }

    /**
     * Lê os blocos de operações de custódia conhecida: primeiro do cache (todas as chaves de uma vez), depois da
     * tabela base só os ausentes, cada operação na sua custódia; os lidos (e as custódias) são gravados no cache.
     *
     * @return {@code texto_detalhe} por operação e bloco; blocos inexistentes ficam ausentes
     */
    private Map<Long, Map<Bloco, byte[]>> lerBlocosNasCustodias(List<OperacaoDoIndice> operacoes, Set<Bloco> blocos) {
        var itens = new HashMap<Long, Map<Bloco, byte[]>>();
        var custodias = new HashMap<Long, Custodia>();
        operacoes.forEach(operacao -> custodias.put(operacao.idOperacao(), operacao.custodia()));
        var chaves = chavesDeBloco(custodias.keySet(), blocos);
        if (chaves.isEmpty()) {
            cache.gravar(Map.of(), custodias);
            return itens;
        }
        var emCache = cache.buscar(chaves);
        emCache.forEach((chave, textoDetalhe) -> guardar(itens, chave, textoDetalhe));

        var ausentes = new ArrayList<ChaveNoDynamoDb>();
        for (var chave : chaves) {
            if (!emCache.containsKey(chave)) {
                ausentes.add(new ChaveNoDynamoDb(chave.idOperacao(), custodias.get(chave.idOperacao()), chave.bloco()));
            }
        }
        var paraGravar = new HashMap<ChaveDeBloco, byte[]>();
        if (!ausentes.isEmpty()) {
            lerDoDynamoDb(ausentes).forEach((idOperacao, porCustodia) -> {
                var daCustodia = porCustodia.getOrDefault(custodias.get(idOperacao), Map.of());
                daCustodia.forEach((bloco, textoDetalhe) -> {
                    var chave = new ChaveDeBloco(idOperacao, bloco);
                    paraGravar.put(chave, textoDetalhe);
                    guardar(itens, chave, textoDetalhe);
                });
            });
        }
        cache.gravar(paraGravar, custodias);
        return itens;
    }

    private static List<ChaveDeBloco> chavesDeBloco(Collection<Long> idsOperacao, Set<Bloco> blocos) {
        var chaves = new ArrayList<ChaveDeBloco>(idsOperacao.size() * blocos.size());
        for (var idOperacao : idsOperacao) {
            for (var bloco : blocos) {
                chaves.add(new ChaveDeBloco(idOperacao, bloco));
            }
        }
        return chaves;
    }

    private static List<ChaveNoDynamoDb> chaves(long idOperacao, Set<Custodia> custodias, Set<Bloco> blocos) {
        var chaves = new ArrayList<ChaveNoDynamoDb>(custodias.size() * blocos.size());
        for (var custodia : custodias) {
            for (var bloco : blocos) {
                chaves.add(new ChaveNoDynamoDb(idOperacao, custodia, bloco));
            }
        }
        return chaves;
    }

    private static Map<Custodia, Map<Bloco, byte[]>> daOperacao(
            Map<Long, Map<Custodia, Map<Bloco, byte[]>>> lidos, long idOperacao) {
        return lidos.getOrDefault(idOperacao, Map.of());
    }

    private static void guardar(Map<Long, Map<Bloco, byte[]>> destino, ChaveDeBloco chave, byte[] textoDetalhe) {
        destino.computeIfAbsent(chave.idOperacao(), id -> new EnumMap<>(Bloco.class)).put(chave.bloco(), textoDetalhe);
    }

    /**
     * Lê os itens na tabela base, em lotes de até 100 chaves. Mais de um lote: em paralelo, uma virtual thread
     * por lote, com o contexto da requisição propagado e no máximo {@code max-lotes-simultaneos} lotes em
     * andamento por requisição (cada lote em andamento ocupa uma conexão do pool). O primeiro lote que falhar
     * derruba a leitura inteira (sem resposta parcial) e interrompe os demais, mesmo que ainda não tenham terminado
     * os anteriores a ele.
     *
     * @return {@code texto_detalhe} por operação, custódia e bloco; itens inexistentes ficam ausentes
     */
    private Map<Long, Map<Custodia, Map<Bloco, byte[]>>> lerDoDynamoDb(List<ChaveNoDynamoDb> chavesNoDynamoDb) {
        var chaves = new ArrayList<Map<String, AttributeValue>>(chavesNoDynamoDb.size());
        for (var chave : chavesNoDynamoDb) {
            chaves.add(Map.of(
                    PK, AttributeValue.fromN(Long.toString(chave.idOperacao())),
                    SK, AttributeValue.fromS(sk(chave.custodia(), chave.bloco()))));
        }
        var lotes = new ArrayList<List<Map<String, AttributeValue>>>();
        for (int inicio = 0; inicio < chaves.size(); inicio += CHAVES_POR_LOTE) {
            lotes.add(chaves.subList(inicio, Math.min(inicio + CHAVES_POR_LOTE, chaves.size())));
        }

        var itens = new HashMap<Long, Map<Custodia, Map<Bloco, byte[]>>>();
        if (lotes.size() <= 1) {
            lotes.forEach(lote -> juntar(itens, lerLote(lote)));
            return itens;
        }
        var vagas = new Semaphore(maxLotesSimultaneos);
        var desistiu = new AtomicBoolean();
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var concluidos = new ExecutorCompletionService<List<Map<String, AttributeValue>>>(executor);
            lotes.forEach(lote -> concluidos.submit(() -> lerLoteNaVez(vagas, desistiu, lote)));
            try {
                for (int i = 0; i < lotes.size(); i++) {
                    juntar(itens, proximoLote(concluidos));
                }
            } catch (RuntimeException e) {
                // Sem resposta parcial: interrompe os lotes em andamento (o circuito ignora as chamadas
                // interrompidas) e desiste dos que esperam vaga
                desistiu.set(true);
                executor.shutdownNow();
                throw e;
            }
        }
        return itens;
    }

    /** Um lote quando houver vaga entre os {@code max-lotes-simultaneos} da requisição, sem esperar além do prazo. */
    private List<Map<String, AttributeValue>> lerLoteNaVez(Semaphore vagas, AtomicBoolean desistiu,
                                                          List<Map<String, AttributeValue>> lote)
            throws InterruptedException {
        var restante = PrazoDaRequisicao.restante();
        if (restante.isEmpty()) {
            vagas.acquire();
        } else if (!vagas.tryAcquire(restante.get().toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TempoEsgotadoNaFonteDeOperacoesException(
                    "Prazo da requisição esgotado antes de ler um lote do BatchGetItem");
        }
        try {
            if (desistiu.get()) {
                throw new CancellationException("Leitura da visão consolidada cancelada por outro lote");
            }
            return lerLote(lote);
        } finally {
            vagas.release();
        }
    }

    /**
     * Um {@code BatchGetItem}, relendo as {@code UnprocessedKeys} com backoff exponencial e jitter. Chaves que
     * sobram depois da última leitura são throttling do DynamoDB: a última leitura falha dentro do circuit breaker
     * (conta como falha) com {@link VisaoConsolidadaSobrecarregadaException} (503).
     */
    private List<Map<String, AttributeValue>> lerLote(List<Map<String, AttributeValue>> chaves) {
        Map<String, KeysAndAttributes> pendentes = Map.of(tabela, KeysAndAttributes.builder()
                .keys(chaves)
                .projectionExpression(PK + ", " + SK + ", " + TEXTO_DETALHE)
                .build());
        var itens = new ArrayList<Map<String, AttributeValue>>();
        for (int leitura = 1; ; leitura++) {
            if (leitura > 1) {
                esperar(leitura);
            }
            var ultima = leitura == MAX_LEITURAS_BATCH;
            var requisicao = pendentes;
            var resposta = resiliencia.chamada("BatchGetItem", () -> {
                var lida = dynamoDb.batchGetItem(b -> b
                        .requestItems(requisicao)
                        .overrideConfiguration(resiliencia.noPrazo()));
                if (ultima && temNaoProcessadas(lida)) {
                    throw new VisaoConsolidadaSobrecarregadaException(
                            "Chaves não processadas pelo DynamoDB após " + MAX_LEITURAS_BATCH + " leituras");
                }
                return lida;
            });
            itens.addAll(resposta.responses().getOrDefault(tabela, List.of()));
            if (!temNaoProcessadas(resposta)) {
                return itens;
            }
            pendentes = resposta.unprocessedKeys();
        }
    }

    private static boolean temNaoProcessadas(BatchGetItemResponse resposta) {
        return resposta.hasUnprocessedKeys() && !resposta.unprocessedKeys().isEmpty();
    }

    /** Backoff com jitter antes de reler as chaves não processadas, só se o prazo comportar a espera e a leitura. */
    private static void esperar(int leitura) {
        var espera = Duration.ofMillis(ThreadLocalRandom.current().nextLong(BACKOFF_BASE_MS << (leitura - 1)));
        if (!PrazoDaRequisicao.comporta(espera.plus(PRAZO_MINIMO_PARA_RELEITURA))) {
            throw new TempoEsgotadoNaFonteDeOperacoesException(
                    "Chaves não processadas pelo DynamoDB e sem prazo para relê-las");
        }
        try {
            Thread.sleep(espera);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaNaFonteDeOperacoesException("Leitura na " + FONTE + " interrompida", e);
        }
    }

    /** Próximo lote concluído (em qualquer ordem), sem esperar além do prazo da requisição. */
    private static <T> T proximoLote(ExecutorCompletionService<T> concluidos) {
        try {
            var restante = PrazoDaRequisicao.restante();
            if (restante.isEmpty()) {
                return concluidos.take().get();
            }
            var concluido = concluidos.poll(restante.get().toNanos(), TimeUnit.NANOSECONDS);
            if (concluido == null) {
                throw new TempoEsgotadoNaFonteDeOperacoesException(
                        "Lotes do BatchGetItem sem resposta no prazo da requisição");
            }
            return concluido.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new FalhaNaFonteDeOperacoesException("Falha na leitura da " + FONTE, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaNaFonteDeOperacoesException("Leitura na " + FONTE + " interrompida", e);
        }
    }

    private static void juntar(Map<Long, Map<Custodia, Map<Bloco, byte[]>>> destino,
                               List<Map<String, AttributeValue>> itens) {
        for (var item : itens) {
            var idOperacao = numeroOperacao(item);
            var chave = custodiaEBloco(item, idOperacao);
            destino.computeIfAbsent(idOperacao, id -> new EnumMap<>(Custodia.class))
                    .computeIfAbsent(chave.custodia(), custodia -> new EnumMap<>(Bloco.class))
                    .put(chave.bloco(), textoDetalhe(item, idOperacao));
        }
    }

    private static long numeroOperacao(Map<String, AttributeValue> item) {
        var valor = item.get(PK);
        if (valor == null || valor.n() == null) {
            throw new VisaoConsolidadaInvalidaException("Item da " + FONTE + " sem " + PK + " numérico");
        }
        try {
            return Long.parseLong(valor.n());
        } catch (NumberFormatException e) {
            throw new VisaoConsolidadaInvalidaException("Item da " + FONTE + " com " + PK + " fora do formato", e);
        }
    }

    /** Custódia e bloco da SK {@code <CUSTODIA>#EXPAND#<BLOCO>}. */
    static CustodiaEBloco custodiaEBloco(Map<String, AttributeValue> item, long idOperacao) {
        var valor = item.get(SK);
        var sk = valor == null ? null : valor.s();
        var separador = sk == null ? -1 : sk.indexOf(INFIXO_SK);
        if (separador < 0) {
            throw new VisaoConsolidadaInvalidaException(
                    "Item da operação " + idOperacao + " na " + FONTE + " sem " + SK + " <CUSTODIA>" + INFIXO_SK
                            + "<BLOCO>");
        }
        var custodia = Custodia.porCodigo(sk.substring(0, separador)).orElseThrow(() ->
                new VisaoConsolidadaInvalidaException(
                        "Item da operação " + idOperacao + " na " + FONTE + " com custódia desconhecida na " + SK));
        try {
            return new CustodiaEBloco(custodia, Bloco.valueOf(sk.substring(separador + INFIXO_SK.length())));
        } catch (IllegalArgumentException e) {
            throw new VisaoConsolidadaInvalidaException(
                    "Item da operação " + idOperacao + " na " + FONTE + " com bloco desconhecido na " + SK, e);
        }
    }

    private static byte[] textoDetalhe(Map<String, AttributeValue> item, long idOperacao) {
        var valor = item.get(TEXTO_DETALHE);
        if (valor == null || valor.b() == null) {
            throw new VisaoConsolidadaInvalidaException(
                    "Item da operação " + idOperacao + " na " + FONTE + " sem " + TEXTO_DETALHE + " binário");
        }
        return valor.b().asByteArray();
    }

    private Object decodificar(byte[] textoDetalhe) {
        return textoDetalhe == null ? null : codec.decodificar(textoDetalhe);
    }

    static String sk(Custodia custodia, Bloco bloco) {
        return custodia.codigo() + INFIXO_SK + bloco.name();
    }

    record CustodiaEBloco(Custodia custodia, Bloco bloco) {
    }

    /** Um item da tabela base: operação, custódia e bloco. */
    private record ChaveNoDynamoDb(long idOperacao, Custodia custodia, Bloco bloco) {
    }

    /** Item do GSI: a operação, a custódia dela e o seu bloco {@code operacao} (projeção {@code ALL}). */
    private record OperacaoDoIndice(long idOperacao, Custodia custodia, byte[] textoDetalhe) {
    }
}
