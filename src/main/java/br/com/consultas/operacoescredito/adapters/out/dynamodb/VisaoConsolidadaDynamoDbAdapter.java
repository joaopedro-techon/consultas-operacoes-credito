package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.stereotype.Component;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Visão consolidada no DynamoDB (ADR-0001): um item por operação e bloco,
 * PK {@code numero_operacao}, SK {@code SF#EXPAND#<BLOCO>}, payload GZIP em {@code texto_detalhe}.
 * <p>
 * OPERACAO lê por chave os blocos pedidos. CLIENTE e CONTA fazem uma {@code Query} no GSI esparso
 * (mais recentes primeiro), que já traz o bloco {@code operacao}, e depois um {@code BatchGetItem}
 * dos demais blocos pedidos, em lotes paralelos de até 100 chaves.
 */
@Component
class VisaoConsolidadaDynamoDbAdapter implements FonteDeOperacoes {

    static final String PK = "numero_operacao";
    static final String SK = "custodia_expand";
    static final String TEXTO_DETALHE = "texto_detalhe";
    static final String ATRIBUTO_CLIENTE = "cpf_cnpj_cliente";
    static final String ATRIBUTO_CONTA = "conta_cliente";
    private static final String PREFIXO_SK = "SF#EXPAND#";
    /** Limite de chaves por {@code BatchGetItem} imposto pelo DynamoDB. */
    private static final int CHAVES_POR_LOTE = 100;
    private static final int MAX_LEITURAS_BATCH = 3;
    private static final long BACKOFF_BASE_MS = 10;

    private final DynamoDbClient dynamoDb;
    private final String tabela;
    private final String indiceCliente;
    private final String indiceConta;
    private final TextoDetalheCodec codec = new TextoDetalheCodec();
    /** Leva MDC (e demais contextos registrados) às virtual threads dos lotes. */
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    VisaoConsolidadaDynamoDbAdapter(DynamoDbClient dynamoDb, DynamoDbProperties properties) {
        this.dynamoDb = dynamoDb;
        this.tabela = properties.tabela();
        this.indiceCliente = properties.indiceCliente();
        this.indiceConta = properties.indiceConta();
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
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
        var operacoes = consultarIndice(indice, atributo, valor, limite);
        if (operacoes.isEmpty()) {
            return List.of();
        }
        var demaisBlocos = EnumSet.noneOf(Bloco.class);
        demaisBlocos.addAll(blocos);
        demaisBlocos.remove(Bloco.OPERACAO);
        var itens = lerBlocos(operacoes.stream().map(OperacaoDoIndice::idOperacao).toList(), demaisBlocos);

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
                    .exclusiveStartKey(inicio);
            if (limite.isPresent()) {
                requisicao.limit(limite.getAsInt() - operacoes.size());
            }
            var resposta = dynamoDb.query(requisicao.build());
            resposta.items().forEach(item -> operacoes.add(new OperacaoDoIndice(
                    Long.parseLong(item.get(PK).n()), item.get(TEXTO_DETALHE).b().asByteArray())));
            inicio = resposta.hasLastEvaluatedKey() && !resposta.lastEvaluatedKey().isEmpty()
                    ? resposta.lastEvaluatedKey()
                    : null;
        } while (inicio != null && (limite.isEmpty() || operacoes.size() < limite.getAsInt()));
        return operacoes;
    }

    /**
     * Lê os blocos das operações na tabela base, em lotes de até 100 chaves. Mais de um lote: em paralelo,
     * uma virtual thread por lote, com o contexto da requisição propagado.
     *
     * @return {@code texto_detalhe} por operação e bloco; blocos inexistentes ficam ausentes
     */
    private Map<Long, Map<Bloco, byte[]>> lerBlocos(Collection<Long> idsOperacao, Set<Bloco> blocos) {
        var chaves = new ArrayList<Map<String, AttributeValue>>();
        for (var idOperacao : idsOperacao) {
            for (var bloco : blocos) {
                chaves.add(Map.of(
                        PK, AttributeValue.fromN(Long.toString(idOperacao)),
                        SK, AttributeValue.fromS(sk(bloco))));
            }
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
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var futuros = lotes.stream().map(lote -> executor.submit(() -> lerLote(lote))).toList();
            try {
                for (var futuro : futuros) {
                    juntar(itens, aguardar(futuro));
                }
            } catch (RuntimeException e) {
                futuros.forEach(futuro -> futuro.cancel(true));
                throw e;
            }
        }
        return itens;
    }

    /** Um {@code BatchGetItem}, relendo as {@code UnprocessedKeys} com backoff exponencial e jitter. */
    private List<Map<String, AttributeValue>> lerLote(List<Map<String, AttributeValue>> chaves) {
        Map<String, KeysAndAttributes> pendentes = Map.of(tabela, KeysAndAttributes.builder()
                .keys(chaves)
                .projectionExpression(PK + ", " + SK + ", " + TEXTO_DETALHE)
                .build());
        var itens = new ArrayList<Map<String, AttributeValue>>();
        for (int leitura = 1; !pendentes.isEmpty(); leitura++) {
            if (leitura > MAX_LEITURAS_BATCH) {
                throw new VisaoConsolidadaIndisponivelException(
                        "Chaves não processadas pelo DynamoDB após " + MAX_LEITURAS_BATCH + " leituras");
            }
            if (leitura > 1) {
                esperar(leitura);
            }
            var requisicao = pendentes;
            var resposta = dynamoDb.batchGetItem(b -> b.requestItems(requisicao));
            itens.addAll(resposta.responses().getOrDefault(tabela, List.of()));
            pendentes = resposta.unprocessedKeys();
        }
        return itens;
    }

    private static void esperar(int leitura) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(BACKOFF_BASE_MS << (leitura - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisaoConsolidadaIndisponivelException("Leitura na visão consolidada interrompida");
        }
    }

    private static <T> T aguardar(Future<T> futuro) {
        try {
            return futuro.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new VisaoConsolidadaIndisponivelException("Falha na leitura da visão consolidada: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisaoConsolidadaIndisponivelException("Leitura na visão consolidada interrompida");
        }
    }

    private static void juntar(Map<Long, Map<Bloco, byte[]>> destino, List<Map<String, AttributeValue>> itens) {
        for (var item : itens) {
            var bloco = Bloco.valueOf(item.get(SK).s().substring(PREFIXO_SK.length()));
            destino.computeIfAbsent(Long.parseLong(item.get(PK).n()), id -> new EnumMap<>(Bloco.class))
                    .put(bloco, item.get(TEXTO_DETALHE).b().asByteArray());
        }
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
