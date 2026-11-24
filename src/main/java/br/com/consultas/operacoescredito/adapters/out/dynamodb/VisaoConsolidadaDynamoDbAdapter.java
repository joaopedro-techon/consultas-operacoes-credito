package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos;
import br.com.consultas.operacoescredito.adapters.out.cache.ChaveDeBloco;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
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
 * Visão consolidada no DynamoDB (ADR-0001): um item por operação e bloco,
 * PK {@code numero_operacao}, SK {@code SF#EXPAND#<BLOCO>}, payload GZIP em {@code texto_detalhe}.
 * <p>
 * OPERACAO lê por chave os blocos pedidos. CLIENTE e CONTA fazem uma {@code Query} no GSI esparso
 * (mais recentes primeiro), que já traz o bloco {@code operacao}, e depois um {@code BatchGetItem}
 * dos demais blocos pedidos, em lotes paralelos de até 100 chaves.
 * <p>
 * Os blocos lidos da tabela base passam pelo {@link CacheDeBlocos} (ADR-0002, ADR-0011): leitura de todas as chaves
 * operação × bloco, {@code BatchGetItem} só dos ausentes e gravação deles no cache (fora da requisição). A {@code Query} no GSI (a lista
 * de operações do cliente/conta, com o bloco {@code operacao}) nunca é cacheada.
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
    static final String ATRIBUTO_CLIENTE = "cpf_cnpj_cliente";
    static final String ATRIBUTO_CONTA = "conta_cliente";
    private static final String PREFIXO_SK = "SF#EXPAND#";
    private static final String FONTE = "visão consolidada";
    /** Limite de chaves por {@code BatchGetItem} imposto pelo DynamoDB. */
    private static final int CHAVES_POR_LOTE = 100;
    private static final int MAX_LEITURAS_BATCH = 3;
    private static final long BACKOFF_BASE_MS = 10;
    /** Prazo que precisa sobrar, além do backoff, para valer reler as {@code UnprocessedKeys}. */
    private static final Duration PRAZO_MINIMO_PARA_RELEITURA = Duration.ofMillis(10);

    static final String ETAPA_CONSULTA_GSI = "consulta_gsi";
    static final String ETAPA_BATCH = "batch";

    private final DynamoDbClient dynamoDb;
    private final ObservationRegistry observationRegistry;
    private final CacheDeBlocos cache;
    private final ResilienciaDynamoDb resiliencia;
    private final String tabela;
    private final String indiceCliente;
    private final String indiceConta;
    private final int maxLotesSimultaneos;
    private final TextoDetalheCodec codec = new TextoDetalheCodec();
    /** Leva MDC (e demais contextos registrados) às virtual threads dos lotes. */
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    VisaoConsolidadaDynamoDbAdapter(DynamoDbClient dynamoDb, DynamoDbProperties properties,
                                    ObservationRegistry observationRegistry, CacheDeBlocos cache,
                                    ResilienciaDynamoDb resiliencia) {
        this.dynamoDb = dynamoDb;
        this.observationRegistry = observationRegistry;
        this.cache = cache;
        this.resiliencia = resiliencia;
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

    private List<OperacaoConsultada> buscarPorOperacao(long idOperacao, Set<Bloco> blocos) {
        var itens = lerBlocos(List.of(idOperacao), blocos).getOrDefault(idOperacao, Map.of());
        if (!itens.containsKey(Bloco.OPERACAO)) {
            return List.of();
        }
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        blocos.forEach(bloco -> conteudo.put(bloco, decodificar(itens.get(bloco))));
        return List.of(new OperacaoConsultada(conteudo));
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
        var ids = operacoes.stream().map(OperacaoDoIndice::idOperacao).toList();
        var itens = Observacoes.etapa(observationRegistry, ETAPA_BATCH)
                .highCardinalityKeyValue("operacoes", Integer.toString(ids.size()))
                .observe(() -> lerBlocos(ids, demaisBlocos));

        var resultado = new ArrayList<OperacaoConsultada>(operacoes.size());
        for (var operacao : operacoes) {
            var itensDaOperacao = itens.getOrDefault(operacao.idOperacao(), Map.of());
            var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
            for (var bloco : blocos) {
                var textoDetalhe = bloco == Bloco.OPERACAO ? operacao.textoDetalhe() : itensDaOperacao.get(bloco);
                conteudo.put(bloco, decodificar(textoDetalhe));
            }
            resultado.add(new OperacaoConsultada(conteudo));
        }
        return resultado;
    }

    /**
     * Query no GSI esparso, mais recentes primeiro. Pagina até o limite (ou até o fim, sem limite):
     * o DynamoDB pode devolver menos itens que o {@code Limit} quando a página passa de 1 MB.
     */
    private List<OperacaoDoIndice> consultarIndice(String indice, String atributo, String valor, OptionalInt limite) {
        var operacoes = new ArrayList<OperacaoDoIndice>();
        Map<String, AttributeValue> inicio = null;
        do {
            var requisicao = QueryRequest.builder()
                    .tableName(tabela)
                    .indexName(indice)
                    .keyConditionExpression("#chave = :valor")
                    .expressionAttributeNames(Map.of("#chave", atributo))
                    .expressionAttributeValues(Map.of(":valor", AttributeValue.fromS(valor)))
                    .scanIndexForward(false)
                    .projectionExpression(PK + ", " + TEXTO_DETALHE)
                    .exclusiveStartKey(inicio)
                    .overrideConfiguration(resiliencia.noPrazo());
            if (limite.isPresent()) {
                requisicao.limit(limite.getAsInt() - operacoes.size());
            }
            var resposta = resiliencia.chamada("Query", () -> dynamoDb.query(requisicao.build()));
            for (var item : resposta.items()) {
                var idOperacao = numeroOperacao(item);
                operacoes.add(new OperacaoDoIndice(idOperacao, textoDetalhe(item, idOperacao)));
            }
            inicio = resposta.hasLastEvaluatedKey() && !resposta.lastEvaluatedKey().isEmpty()
                    ? resposta.lastEvaluatedKey()
                    : null;
        } while (inicio != null && (limite.isEmpty() || operacoes.size() < limite.getAsInt()));
        return operacoes;
    }

    /**
     * Lê os blocos das operações: primeiro do cache (todas as chaves de uma vez), depois da tabela base
     * só os ausentes, que são gravados no cache. Bloco inexistente no DynamoDB não é gravado.
     *
     * @return {@code texto_detalhe} por operação e bloco; blocos inexistentes ficam ausentes
     */
    private Map<Long, Map<Bloco, byte[]>> lerBlocos(Collection<Long> idsOperacao, Set<Bloco> blocos) {
        var chaves = new ArrayList<ChaveDeBloco>(idsOperacao.size() * blocos.size());
        for (var idOperacao : idsOperacao) {
            for (var bloco : blocos) {
                chaves.add(new ChaveDeBloco(idOperacao, bloco));
            }
        }
        var itens = new HashMap<Long, Map<Bloco, byte[]>>();
        if (chaves.isEmpty()) {
            return itens;
        }
        var emCache = cache.buscar(chaves);
        emCache.forEach((chave, textoDetalhe) -> guardar(itens, chave, textoDetalhe));

        var ausentes = chaves.stream().filter(chave -> !emCache.containsKey(chave)).toList();
        if (ausentes.isEmpty()) {
            return itens;
        }
        var lidos = lerDoDynamoDb(ausentes);
        var paraGravar = new HashMap<ChaveDeBloco, byte[]>();
        lidos.forEach((idOperacao, porBloco) -> porBloco.forEach((bloco, textoDetalhe) -> {
            var chave = new ChaveDeBloco(idOperacao, bloco);
            paraGravar.put(chave, textoDetalhe);
            guardar(itens, chave, textoDetalhe);
        }));
        cache.gravar(paraGravar);
        return itens;
    }

    private static void guardar(Map<Long, Map<Bloco, byte[]>> destino, ChaveDeBloco chave, byte[] textoDetalhe) {
        destino.computeIfAbsent(chave.idOperacao(), id -> new EnumMap<>(Bloco.class)).put(chave.bloco(), textoDetalhe);
    }

    /**
     * Lê os blocos na tabela base, em lotes de até 100 chaves. Mais de um lote: em paralelo, uma virtual thread
     * por lote, com o contexto da requisição propagado e no máximo {@code max-lotes-simultaneos} lotes em
     * andamento por requisição (cada lote em andamento ocupa uma conexão do pool). O primeiro lote que falhar
     * derruba a leitura inteira (sem resposta parcial) e interrompe os demais, mesmo que ainda não tenham terminado
     * os anteriores a ele.
     */
    private Map<Long, Map<Bloco, byte[]>> lerDoDynamoDb(List<ChaveDeBloco> chavesDeBloco) {
        var chaves = new ArrayList<Map<String, AttributeValue>>(chavesDeBloco.size());
        for (var chave : chavesDeBloco) {
            chaves.add(Map.of(
                    PK, AttributeValue.fromN(Long.toString(chave.idOperacao())),
                    SK, AttributeValue.fromS(sk(chave.bloco()))));
        }
        var lotes = new ArrayList<List<Map<String, AttributeValue>>>();
        for (int inicio = 0; inicio < chaves.size(); inicio += CHAVES_POR_LOTE) {
            lotes.add(chaves.subList(inicio, Math.min(inicio + CHAVES_POR_LOTE, chaves.size())));
        }

        var itens = new HashMap<Long, Map<Bloco, byte[]>>();
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

    private static void juntar(Map<Long, Map<Bloco, byte[]>> destino, List<Map<String, AttributeValue>> itens) {
        for (var item : itens) {
            var idOperacao = numeroOperacao(item);
            destino.computeIfAbsent(idOperacao, id -> new EnumMap<>(Bloco.class))
                    .put(bloco(item, idOperacao), textoDetalhe(item, idOperacao));
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

    private static Bloco bloco(Map<String, AttributeValue> item, long idOperacao) {
        var valor = item.get(SK);
        if (valor == null || valor.s() == null || !valor.s().startsWith(PREFIXO_SK)) {
            throw new VisaoConsolidadaInvalidaException(
                    "Item da operação " + idOperacao + " na " + FONTE + " sem " + SK + " " + PREFIXO_SK + "<BLOCO>");
        }
        try {
            return Bloco.valueOf(valor.s().substring(PREFIXO_SK.length()));
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

    private static String sk(Bloco bloco) {
        return PREFIXO_SK + bloco.name();
    }

    /** Item do GSI: a operação e o seu bloco {@code operacao} (projeção {@code ALL}). */
    private record OperacaoDoIndice(long idOperacao, byte[] textoDetalhe) {
    }
}
