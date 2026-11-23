package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import org.springframework.stereotype.Component;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Visão consolidada no DynamoDB (ADR-0001): um item por operação e bloco,
 * PK {@code numero_operacao}, SK {@code SF#EXPAND#<BLOCO>}, payload GZIP em {@code texto_detalhe}.
 */
@Component
class VisaoConsolidadaDynamoDbAdapter implements FonteDeOperacoes {

    static final String PK = "numero_operacao";
    static final String SK = "custodia_expand";
    static final String TEXTO_DETALHE = "texto_detalhe";
    private static final String PREFIXO_SK = "SF#EXPAND#";
    private static final int MAX_LEITURAS_BATCH = 3;

    private final DynamoDbClient dynamoDb;
    private final String tabela;
    private final TextoDetalheCodec codec = new TextoDetalheCodec();

    VisaoConsolidadaDynamoDbAdapter(DynamoDbClient dynamoDb, DynamoDbProperties properties) {
        this.dynamoDb = dynamoDb;
        this.tabela = properties.tabela();
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
        return switch (criterio) {
            case CriterioConsulta.PorOperacao porOperacao -> buscarPorOperacao(porOperacao.idOperacao(), blocos);
        };
    }

    private List<OperacaoConsultada> buscarPorOperacao(long idOperacao, Set<Bloco> blocos) {
        var itensPorSk = lerItens(idOperacao, blocos);
        if (!itensPorSk.containsKey(sk(Bloco.OPERACAO))) {
            return List.of();
        }
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        for (var bloco : blocos) {
            var item = itensPorSk.get(sk(bloco));
            conteudo.put(bloco, item == null ? null : codec.decodificar(item.get(TEXTO_DETALHE).b().asByteArray()));
        }
        return List.of(new OperacaoConsultada(conteudo));
    }

    /** BatchGetItem com as chaves dos blocos pedidos, relendo as {@code UnprocessedKeys}. */
    private Map<String, Map<String, AttributeValue>> lerItens(long idOperacao, Set<Bloco> blocos) {
        var chaves = blocos.stream()
                .map(bloco -> Map.of(
                        PK, AttributeValue.fromN(Long.toString(idOperacao)),
                        SK, AttributeValue.fromS(sk(bloco))))
                .toList();
        Map<String, KeysAndAttributes> pendentes = Map.of(tabela, KeysAndAttributes.builder()
                .keys(chaves)
                .projectionExpression(SK + ", " + TEXTO_DETALHE)
                .build());

        var itensPorSk = new HashMap<String, Map<String, AttributeValue>>();
        for (int leitura = 1; !pendentes.isEmpty(); leitura++) {
            if (leitura > MAX_LEITURAS_BATCH) {
                throw new VisaoConsolidadaIndisponivelException(
                        "Chaves não processadas pelo DynamoDB após " + MAX_LEITURAS_BATCH + " leituras");
            }
            var requisicao = pendentes;
            var resposta = dynamoDb.batchGetItem(b -> b.requestItems(requisicao));
            resposta.responses().getOrDefault(tabela, List.of())
                    .forEach(item -> itensPorSk.put(item.get(SK).s(), item));
            pendentes = resposta.unprocessedKeys();
        }
        return itensPorSk;
    }

    private static String sk(Bloco bloco) {
        return PREFIXO_SK + bloco.name();
    }
}
