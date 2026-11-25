package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.BlocosEmCache;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.GravacaoDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.GravacaoDeCustodia;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeCustodia;
import br.com.consultas.operacoescredito.adapters.out.cache.ChaveDeOperacao;
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
import java.util.Optional;
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
 * PK {@code numero_operacao}, SK {@code <CUSTODIA>#EXPAND#<BLOCO>}, payload GZIP em {@code texto_detalhe} e versão
 * do registro em {@code codigo_identificador_carga}.
 * <p>
 * Descoberta de custódia (ADR-0012):
 * <ul>
 *     <li>OPERACAO: a custódia vem do cache de custódia, na mesma ida ao Valkey dos blocos. Conhecida, o
 *     {@code BatchGetItem} pede só os blocos ausentes do cache nela; se ela não tem mais a operação, ou se não
 *     estava em cache, os blocos são pedidos nas quatro custódias (até 36 chaves, um lote) e vale a de maior
 *     {@link PrecedenciaDeCustodia precedência} entre as que têm o bloco {@code operacao};</li>
 *     <li>CLIENTE e CONTA: {@code Query} no GSI esparso (mais recentes primeiro), que já traz o bloco
 *     {@code operacao} e a SK (logo, a custódia) de cada operação; operação repetida em mais de uma custódia fica
 *     com a de maior precedência. Depois, os demais blocos na custódia de cada operação: do cache, se estiverem lá
 *     na versão do bloco {@code operacao}, e o resto num {@code BatchGetItem} em lotes paralelos de até 100 chaves.
 *     </li>
 * </ul>
 * Todos os blocos de uma operação vêm da mesma custódia, e ela volta no campo {@code custodia} do bloco
 * {@code operacao}.
 * <p>
 * <b>Versão única (ADR-0018).</b> Uma consulta nunca entrega blocos de versões do registro diferentes da mesma
 * operação. O {@code BatchGetItem} não é isolado das transações dos escritores, e a leitura eventualmente
 * consistente e o GSI podem estar atrasados: se os blocos de uma operação (os do cache, os do GSI e os da tabela
 * base) divergem, todos são relidos da tabela base com {@code ConsistentRead}, no máximo
 * {@value #MAX_RELEITURAS_CONSISTENTES} vezes; divergência que persiste, ou falta de prazo para reler, é
 * {@link VersaoDivergenteException} (503). Só vão ao cache blocos com versão em epoch ms; sem versão (ou fora do
 * formato), a consulta é respondida, mas nada é gravado.
 * <p>
 * Resiliência ({@link ResilienciaDynamoDb}): a consulta passa pelos bulkheads do tipo de consulta e pelo rate
 * limiter; cada chamada ao SDK, pelo circuit breaker, com {@code apiCallTimeout} limitado ao que resta do
 * {@link PrazoDaRequisicao prazo da requisição}. Os lotes paralelos de uma requisição são no máximo
 * {@code consultas.dynamodb.max-lotes-simultaneos} ao mesmo tempo; a espera por vaga, a espera pelos lotes e a
 * releitura das {@code UnprocessedKeys} também respeitam o prazo: estourado, 504. Item fora do formato
 * ({@link VisaoConsolidadaInvalidaException}): 502.
 */
@Component
class VisaoConsolidadaDynamoDbAdapter implements FonteDeOperacoes {

    static final String PK = "numero_operacao";
    static final String SK = "custodia_expand";
    static final String TEXTO_DETALHE = "texto_detalhe";
    static final String VERSAO = "codigo_identificador_carga";
    static final String ATRIBUTO_CLIENTE = "cpf_cnpj_cliente";
    static final String ATRIBUTO_CONTA = "conta_cliente";
    /** Separador entre a custódia e o bloco na SK: {@code <CUSTODIA>#EXPAND#<BLOCO>}. */
    static final String INFIXO_SK = "#EXPAND#";
    /** Valor da tag {@code rota} das métricas de custódia. */
    static final String ROTA = "visao_consolidada";
    /** Operações cujos blocos vieram em versões diferentes, por {@code resultado=relida|falhou}. */
    static final String METRICA_VERSAO_DIVERGENTE = "consultas.versao_divergente";
    /** Releituras consistentes de uma operação com blocos em versões diferentes, antes do 503. */
    static final int MAX_RELEITURAS_CONSISTENTES = 2;
    private static final String FONTE = "visão consolidada";
    private static final String PROJECAO = PK + ", " + SK + ", " + TEXTO_DETALHE + ", " + VERSAO;
    /** Limite de chaves por {@code BatchGetItem} imposto pelo DynamoDB. */
    private static final int CHAVES_POR_LOTE = 100;
    private static final int MAX_LEITURAS_BATCH = 3;
    private static final long BACKOFF_BASE_MS = 10;
    /** Prazo que precisa sobrar, além do backoff, para valer reler as {@code UnprocessedKeys}. */
    private static final Duration PRAZO_MINIMO_PARA_RELEITURA = Duration.ofMillis(10);
    private static final Set<Custodia> TODAS = EnumSet.allOf(Custodia.class);
    private static final Logger log = LoggerFactory.getLogger(VisaoConsolidadaDynamoDbAdapter.class);

    static final String ETAPA_CONSULTA_GSI = "consulta_gsi";
    static final String ETAPA_BATCH = "batch";

    private final DynamoDbClient dynamoDb;
    private final ObservationRegistry observationRegistry;
    private final CacheDeBlocos cache;
    private final CacheDeCustodia cacheDeCustodia;
    private final ResilienciaDynamoDb resiliencia;
    private final PrecedenciaDeCustodia precedencia;
    private final MetricasDeCustodia metricas;
    private final Counter divergentesRelidas;
    private final Counter divergentesFalhas;
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
        this.divergentesRelidas = contadorDeDivergencia(meterRegistry, "relida");
        this.divergentesFalhas = contadorDeDivergencia(meterRegistry, "falhou");
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

    // ---------------------------------------------------------------------------------------------------------
    // OPERACAO
    // ---------------------------------------------------------------------------------------------------------

    /** Pela custódia do cache, se houver e ainda tiver a operação; senão, descoberta nas quatro custódias. */
    private List<OperacaoConsultada> buscarPorOperacao(long idOperacao, Set<Bloco> blocos) {
        var leitura = cache.buscarOperacao(idOperacao, blocos);
        var lembrada = leitura.custodia();
        if (lembrada.isPresent()) {
            var custodia = lembrada.get().custodia();
            metricas.contar(custodia, MetricasDeCustodia.Resultado.CACHE);
            var itens = naCustodiaLembrada(idOperacao, custodia, blocos, leitura.blocos());
            if (itens.isPresent()) {
                return List.of(montar(custodia, blocos, itens.get()));
            }
            // A operação não está mais na custódia lembrada (migrou ou deixou de existir): descoberta completa
        }
        return descobrir(idOperacao, blocos, lembrada.isPresent());
    }

    /**
     * Os blocos na custódia lembrada: os do cache e, do DynamoDB, os que faltam. Se os do DynamoDB são de outra
     * versão (o cache ficou para trás, ou a leitura eventual está atrasada), relê todos.
     *
     * @return vazio se a custódia não tem mais a operação
     */
    private Optional<Map<Bloco, ItemLido>> naCustodiaLembrada(long idOperacao, Custodia custodia, Set<Bloco> blocos,
                                                              Optional<BlocosEmCache> emCache) {
        var itens = new EnumMap<Bloco, ItemLido>(Bloco.class);
        emCache.ifPresent(blocosDoHash -> blocosDoHash.blocos().forEach((bloco, textoDetalhe) ->
                itens.put(bloco, new ItemLido(textoDetalhe, VersaoDoRegistro.doCache(blocosDoHash.versao())))));
        var ausentes = EnumSet.noneOf(Bloco.class);
        blocos.stream().filter(bloco -> !itens.containsKey(bloco)).forEach(ausentes::add);
        if (ausentes.isEmpty()) {
            return Optional.of(itens);
        }
        var lidos = daOperacao(lerDoDynamoDb(chaves(idOperacao, Set.of(custodia), ausentes), false), idOperacao)
                .getOrDefault(custodia, Map.of());
        itens.putAll(lidos);
        if (!itens.containsKey(Bloco.OPERACAO)) {
            return Optional.empty();
        }
        if (versaoUnica(itens.values()).isEmpty()) {
            var relidos = releituraConsistente(idOperacao, custodia, blocos);
            relidos.ifPresent(todos -> gravarNoCache(idOperacao, custodia, todos, todos));
            return relidos;
        }
        gravarNoCache(idOperacao, custodia, itens, lidos);
        return Optional.of(itens);
    }

    private List<OperacaoConsultada> descobrir(long idOperacao, Set<Bloco> blocos, boolean haviaLembrada) {
        var porCustodia = daOperacao(lerDoDynamoDb(chaves(idOperacao, TODAS, blocos), false), idOperacao);
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
        if (versaoUnica(itens.values()).isEmpty()) {
            itens = releituraConsistente(idOperacao, vencedora.get(), blocos).orElseThrow(() ->
                    divergente("A operação " + idOperacao + " sumiu da custódia " + vencedora.get().codigo()
                            + " na releitura consistente"));
        }
        gravarNoCache(idOperacao, vencedora.get(), itens, itens);
        return List.of(montar(vencedora.get(), blocos, itens));
    }

    /**
     * Relê todos os blocos da operação na custódia com {@code ConsistentRead}, até que venham numa versão só.
     *
     * @return vazio se a custódia não tem a operação
     */
    private Optional<Map<Bloco, ItemLido>> releituraConsistente(long idOperacao, Custodia custodia,
                                                                Set<Bloco> blocos) {
        for (int releitura = 1; releitura <= MAX_RELEITURAS_CONSISTENTES; releitura++) {
            exigirPrazoParaReler(idOperacao);
            var lidos = daOperacao(lerDoDynamoDb(chaves(idOperacao, Set.of(custodia), blocos), true), idOperacao)
                    .getOrDefault(custodia, Map.of());
            if (!lidos.containsKey(Bloco.OPERACAO)) {
                return Optional.empty();
            }
            if (versaoUnica(lidos.values()).isPresent()) {
                divergentesRelidas.increment();
                return Optional.of(lidos);
            }
        }
        throw divergente("Blocos da operação " + idOperacao + " em versões diferentes depois de "
                + MAX_RELEITURAS_CONSISTENTES + " releituras consistentes");
    }

    /**
     * Grava no cache, se a versão estiver em epoch ms, os blocos lidos do DynamoDB e a custódia (na versão de
     * {@code itens}, todos de uma versão só).
     */
    private void gravarNoCache(long idOperacao, Custodia custodia, Map<Bloco, ItemLido> itens,
                               Map<Bloco, ItemLido> lidosDoDynamoDb) {
        var versao = versaoUnica(itens.values()).flatMap(unica -> {
            var epoch = unica.epochMs();
            return epoch.isPresent() ? Optional.of(epoch.getAsLong()) : Optional.empty();
        });
        if (versao.isEmpty()) {
            log.debug("Operação {} sem versão do registro em epoch ms; nada gravado no cache", idOperacao);
            return;
        }
        cache.gravar(List.of(new GravacaoDeBlocos(new ChaveDeOperacao(idOperacao, custodia), versao.get(),
                        textos(lidosDoDynamoDb))),
                List.of(new GravacaoDeCustodia(idOperacao, custodia, versao.get())));
    }

    // ---------------------------------------------------------------------------------------------------------
    // CLIENTE e CONTA
    // ---------------------------------------------------------------------------------------------------------

    private List<OperacaoConsultada> buscarPorIndice(String indice, String atributo, String valor,
                                                     OptionalInt limite, Set<Bloco> blocos) {
        var operacoes = Observacoes.etapa(observationRegistry, ETAPA_CONSULTA_GSI)
                .lowCardinalityKeyValue("indice", indice)
                .observe(() -> consultarIndice(indice, atributo, valor, limite));
        if (operacoes.isEmpty()) {
            return List.of();
        }
        var itens = Observacoes.etapa(observationRegistry, ETAPA_BATCH)
                .highCardinalityKeyValue("operacoes", Integer.toString(operacoes.size()))
                .observe(() -> lerBlocosNasCustodias(operacoes, blocos));

        var resultado = new ArrayList<OperacaoConsultada>(operacoes.size());
        for (var operacao : operacoes) {
            resultado.add(montar(operacao.custodia(), blocos, itens.get(operacao.idOperacao())));
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
                    .projectionExpression(PROJECAO)
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
                        .add(new OperacaoDoIndice(idOperacao, chave.custodia(),
                                new ItemLido(textoDetalhe(item, idOperacao), VersaoDoRegistro.de(item.get(VERSAO)))));
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
     * Os blocos de operações de custódia conhecida, numa versão só por operação: o {@code operacao} do GSI, os
     * demais do cache (só se estiverem lá na versão do GSI) e, da tabela base, os ausentes, cada operação na sua
     * custódia. Operações com versões divergentes são relidas inteiras com {@code ConsistentRead}. Os blocos lidos
     * da tabela base (exceto o {@code operacao}) e as custódias vão ao cache.
     *
     * @return itens por operação e bloco; blocos inexistentes ficam ausentes
     */
    private Map<Long, Map<Bloco, ItemLido>> lerBlocosNasCustodias(List<OperacaoDoIndice> operacoes,
                                                                  Set<Bloco> blocos) {
        var demaisBlocos = EnumSet.noneOf(Bloco.class);
        demaisBlocos.addAll(blocos);
        demaisBlocos.remove(Bloco.OPERACAO);

        var itens = new HashMap<Long, Map<Bloco, ItemLido>>();
        var custodias = new HashMap<Long, Custodia>();
        var versoesConhecidas = new HashMap<ChaveDeOperacao, Long>();
        for (var operacao : operacoes) {
            var doIndice = new EnumMap<Bloco, ItemLido>(Bloco.class);
            doIndice.put(Bloco.OPERACAO, operacao.operacao());
            itens.put(operacao.idOperacao(), doIndice);
            custodias.put(operacao.idOperacao(), operacao.custodia());
            operacao.operacao().versao().epochMs().ifPresent(epoch ->
                    versoesConhecidas.put(new ChaveDeOperacao(operacao.idOperacao(), operacao.custodia()), epoch));
        }
        cache.buscar(versoesConhecidas, demaisBlocos).forEach((chave, blocosDoHash) ->
                blocosDoHash.blocos().forEach((bloco, textoDetalhe) -> itens.get(chave.idOperacao()).put(bloco,
                        new ItemLido(textoDetalhe, VersaoDoRegistro.doCache(blocosDoHash.versao())))));

        var ausentes = new ArrayList<ChaveNoDynamoDb>();
        for (var operacao : operacoes) {
            for (var bloco : demaisBlocos) {
                if (!itens.get(operacao.idOperacao()).containsKey(bloco)) {
                    ausentes.add(new ChaveNoDynamoDb(operacao.idOperacao(), operacao.custodia(), bloco));
                }
            }
        }
        var lidosDoDynamoDb = new HashMap<Long, Map<Bloco, ItemLido>>();
        if (!ausentes.isEmpty()) {
            lerDoDynamoDb(ausentes, false).forEach((idOperacao, porCustodia) -> {
                var daCustodia = porCustodia.getOrDefault(custodias.get(idOperacao), Map.of());
                itens.get(idOperacao).putAll(daCustodia);
                lidosDoDynamoDb.computeIfAbsent(idOperacao, id -> new EnumMap<>(Bloco.class)).putAll(daCustodia);
            });
        }

        var divergentes = new LinkedHashMap<Long, Custodia>();
        itens.forEach((idOperacao, daOperacao) -> {
            if (versaoUnica(daOperacao.values()).isEmpty()) {
                divergentes.put(idOperacao, custodias.get(idOperacao));
            }
        });
        if (!divergentes.isEmpty()) {
            releituraConsistente(divergentes, blocos).forEach((idOperacao, relidos) -> {
                itens.put(idOperacao, relidos);
                lidosDoDynamoDb.put(idOperacao, relidos);
            });
        }

        gravarNoCache(operacoes, itens, lidosDoDynamoDb);
        return itens;
    }

    /** {@link #releituraConsistente(long, Custodia, Set)} de várias operações, em lote. */
    private Map<Long, Map<Bloco, ItemLido>> releituraConsistente(Map<Long, Custodia> divergentes,
                                                                 Set<Bloco> blocos) {
        var relidas = new HashMap<Long, Map<Bloco, ItemLido>>();
        var pendentes = divergentes;
        for (int releitura = 1; releitura <= MAX_RELEITURAS_CONSISTENTES; releitura++) {
            exigirPrazoParaReler(pendentes.keySet().iterator().next());
            var chaves = new ArrayList<ChaveNoDynamoDb>();
            pendentes.forEach((idOperacao, custodia) -> chaves.addAll(chaves(idOperacao, Set.of(custodia), blocos)));
            var lidos = lerDoDynamoDb(chaves, true);
            var aindaDivergentes = new LinkedHashMap<Long, Custodia>();
            pendentes.forEach((idOperacao, custodia) -> {
                var daCustodia = daOperacao(lidos, idOperacao).getOrDefault(custodia, Map.of());
                if (daCustodia.containsKey(Bloco.OPERACAO) && versaoUnica(daCustodia.values()).isPresent()) {
                    divergentesRelidas.increment();
                    relidas.put(idOperacao, daCustodia);
                } else {
                    // Sem o bloco operacao, o GSI aponta uma custódia que já não tem a operação: segue divergente
                    aindaDivergentes.put(idOperacao, custodia);
                }
            });
            if (aindaDivergentes.isEmpty()) {
                return relidas;
            }
            pendentes = aindaDivergentes;
        }
        throw divergente("Blocos das operações " + pendentes.keySet() + " em versões diferentes depois de "
                + MAX_RELEITURAS_CONSISTENTES + " releituras consistentes");
    }

    /**
     * Grava no cache os blocos lidos da tabela base, exceto o {@code operacao} (que veio do GSI e é relido a cada
     * consulta), e a custódia de cada operação, com a versão da operação, se estiver em epoch ms.
     */
    private void gravarNoCache(List<OperacaoDoIndice> operacoes, Map<Long, Map<Bloco, ItemLido>> itens,
                               Map<Long, Map<Bloco, ItemLido>> lidosDoDynamoDb) {
        var blocos = new ArrayList<GravacaoDeBlocos>();
        var custodias = new ArrayList<GravacaoDeCustodia>();
        for (var operacao : operacoes) {
            var idOperacao = operacao.idOperacao();
            var epoch = versaoUnica(itens.get(idOperacao).values()).map(VersaoDoRegistro::epochMs);
            if (epoch.isEmpty() || epoch.get().isEmpty()) {
                continue;
            }
            var versao = epoch.get().getAsLong();
            custodias.add(new GravacaoDeCustodia(idOperacao, operacao.custodia(), versao));
            var lidos = new EnumMap<Bloco, ItemLido>(Bloco.class);
            lidos.putAll(lidosDoDynamoDb.getOrDefault(idOperacao, Map.of()));
            lidos.remove(Bloco.OPERACAO);
            if (!lidos.isEmpty()) {
                blocos.add(new GravacaoDeBlocos(new ChaveDeOperacao(idOperacao, operacao.custodia()), versao,
                        textos(lidos)));
            }
        }
        cache.gravar(blocos, custodias);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Versão do registro
    // ---------------------------------------------------------------------------------------------------------

    /** A versão comum a todos os itens, se houver uma só; vazio se divergem (ou se não há itens). */
    private static Optional<VersaoDoRegistro> versaoUnica(Collection<ItemLido> itens) {
        VersaoDoRegistro unica = null;
        for (var item : itens) {
            if (unica == null) {
                unica = item.versao();
            } else if (!unica.mesma(item.versao())) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(unica);
    }

    /** Sem prazo para uma releitura, a divergência fica sem solução nesta requisição: 503, não 504. */
    private void exigirPrazoParaReler(long idOperacao) {
        if (!PrazoDaRequisicao.comporta(PRAZO_MINIMO_PARA_RELEITURA)) {
            throw divergente("Blocos da operação " + idOperacao
                    + " em versões diferentes e sem prazo para relê-los");
        }
    }

    private VersaoDivergenteException divergente(String mensagem) {
        divergentesFalhas.increment();
        log.warn(mensagem);
        return new VersaoDivergenteException(mensagem);
    }

    private static Counter contadorDeDivergencia(MeterRegistry registry, String resultado) {
        return Counter.builder(METRICA_VERSAO_DIVERGENTE)
                .description("Operações cujos blocos vieram da visão consolidada em versões do registro diferentes")
                .tag("resultado", resultado)
                .register(registry);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Leitura na tabela base
    // ---------------------------------------------------------------------------------------------------------

    private OperacaoConsultada montar(Custodia custodia, Set<Bloco> blocos, Map<Bloco, ItemLido> itens) {
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        blocos.forEach(bloco -> {
            var item = itens.get(bloco);
            conteudo.put(bloco, item == null ? null : codec.decodificar(item.textoDetalhe()));
        });
        return OperacaoConsultada.naCustodia(custodia, conteudo);
    }

    private static Map<Bloco, byte[]> textos(Map<Bloco, ItemLido> itens) {
        var textos = new EnumMap<Bloco, byte[]>(Bloco.class);
        itens.forEach((bloco, item) -> textos.put(bloco, item.textoDetalhe()));
        return textos;
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

    private static Map<Custodia, Map<Bloco, ItemLido>> daOperacao(
            Map<Long, Map<Custodia, Map<Bloco, ItemLido>>> lidos, long idOperacao) {
        return lidos.getOrDefault(idOperacao, Map.of());
    }

    /**
     * Lê os itens na tabela base, em lotes de até 100 chaves. Mais de um lote: em paralelo, uma virtual thread
     * por lote, com o contexto da requisição propagado e no máximo {@code max-lotes-simultaneos} lotes em
     * andamento por requisição (cada lote em andamento ocupa uma conexão do pool). O primeiro lote que falhar
     * derruba a leitura inteira (sem resposta parcial) e interrompe os demais, mesmo que ainda não tenham terminado
     * os anteriores a ele.
     *
     * @param consistente {@code ConsistentRead} (releitura de blocos em versões diferentes)
     * @return itens por operação, custódia e bloco; itens inexistentes ficam ausentes
     */
    private Map<Long, Map<Custodia, Map<Bloco, ItemLido>>> lerDoDynamoDb(List<ChaveNoDynamoDb> chavesNoDynamoDb,
                                                                         boolean consistente) {
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

        var itens = new HashMap<Long, Map<Custodia, Map<Bloco, ItemLido>>>();
        if (lotes.size() <= 1) {
            lotes.forEach(lote -> juntar(itens, lerLote(lote, consistente)));
            return itens;
        }
        var vagas = new Semaphore(maxLotesSimultaneos);
        var desistiu = new AtomicBoolean();
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var concluidos = new ExecutorCompletionService<List<Map<String, AttributeValue>>>(executor);
            lotes.forEach(lote -> concluidos.submit(() -> lerLoteNaVez(vagas, desistiu, lote, consistente)));
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
                                                          List<Map<String, AttributeValue>> lote,
                                                          boolean consistente)
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
            return lerLote(lote, consistente);
        } finally {
            vagas.release();
        }
    }

    /**
     * Um {@code BatchGetItem}, relendo as {@code UnprocessedKeys} com backoff exponencial e jitter. Chaves que
     * sobram depois da última leitura são throttling do DynamoDB: a última leitura falha dentro do circuit breaker
     * (conta como falha) com {@link VisaoConsolidadaSobrecarregadaException} (503).
     */
    private List<Map<String, AttributeValue>> lerLote(List<Map<String, AttributeValue>> chaves, boolean consistente) {
        Map<String, KeysAndAttributes> pendentes = Map.of(tabela, KeysAndAttributes.builder()
                .keys(chaves)
                .projectionExpression(PROJECAO)
                .consistentRead(consistente)
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

    private static void juntar(Map<Long, Map<Custodia, Map<Bloco, ItemLido>>> destino,
                               List<Map<String, AttributeValue>> itens) {
        for (var item : itens) {
            var idOperacao = numeroOperacao(item);
            var chave = custodiaEBloco(item, idOperacao);
            destino.computeIfAbsent(idOperacao, id -> new EnumMap<>(Custodia.class))
                    .computeIfAbsent(chave.custodia(), custodia -> new EnumMap<>(Bloco.class))
                    .put(chave.bloco(), new ItemLido(textoDetalhe(item, idOperacao),
                            VersaoDoRegistro.de(item.get(VERSAO))));
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

    static String sk(Custodia custodia, Bloco bloco) {
        return custodia.codigo() + INFIXO_SK + bloco.name();
    }

    record CustodiaEBloco(Custodia custodia, Bloco bloco) {
    }

    /** Um bloco lido (da tabela base, do GSI ou do cache) e a versão do registro dele. */
    private record ItemLido(byte[] textoDetalhe, VersaoDoRegistro versao) {
    }

    /** Um item da tabela base: operação, custódia e bloco. */
    private record ChaveNoDynamoDb(long idOperacao, Custodia custodia, Bloco bloco) {
    }

    /** Item do GSI: a operação, a custódia dela e o seu bloco {@code operacao} (projeção {@code ALL}). */
    private record OperacaoDoIndice(long idOperacao, Custodia custodia, ItemLido operacao) {
    }
}
