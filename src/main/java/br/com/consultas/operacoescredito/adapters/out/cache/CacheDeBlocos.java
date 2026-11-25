package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Cache versionado da visão consolidada (ADR-0018): um hash por operação e custódia com os blocos de uma única
 * versão do registro, e a custódia lembrada de cada operação, também com versão. Os blocos são o
 * {@code texto_detalhe} (JSON em GZIP) exatamente como veio do DynamoDB.
 * <p>
 * Uma gravação nunca troca a versão do cache por uma menor, e um hash com o marcador de invalidação conta como
 * ausente. É só uma otimização (fail-open): nenhum método lança exceção por falha do cache; falha, lentidão ou
 * circuit breaker aberto equivalem a "nada em cache" na leitura e a "nada gravado" na escrita.
 */
public interface CacheDeBlocos {

    /**
     * Consulta OPERACAO, numa só ida ao Valkey: a custódia lembrada da operação e os blocos pedidos que o cache
     * tem nela.
     */
    LeituraDaOperacao buscarOperacao(long idOperacao, Set<Bloco> blocos);

    /**
     * Consultas CLIENTE e CONTA, de operações com custódia e versão já conhecidas (do GSI): os blocos pedidos que o
     * cache tem <b>nessa versão</b>. Operação cujo cache tem outra versão (ou o marcador) não aparece.
     */
    Map<ChaveDeOperacao, BlocosEmCache> buscar(Map<ChaveDeOperacao, Long> versoes, Set<Bloco> blocos);

    /** Grava blocos e custódias com a regra de versão, em best-effort: pode voltar antes de a gravação terminar. */
    void gravar(Collection<GravacaoDeBlocos> blocos, Collection<GravacaoDeCustodia> custodias);

    /**
     * Blocos de uma operação em cache, todos da mesma versão.
     *
     * @param versao versão do registro, em epoch ms
     * @param blocos {@code texto_detalhe} dos blocos pedidos que estavam no hash; os ausentes não aparecem
     */
    record BlocosEmCache(long versao, Map<Bloco, byte[]> blocos) {

        public BlocosEmCache {
            blocos = Map.copyOf(blocos);
        }
    }

    /** Custódia lembrada de uma operação e a versão do registro em que ela foi vista. */
    record CustodiaLembrada(Custodia custodia, long versao) {

        public CustodiaLembrada {
            Objects.requireNonNull(custodia, "custodia");
        }
    }

    /**
     * O que a leitura da consulta OPERACAO encontrou.
     *
     * @param custodia custódia lembrada; vazia sem lembrança, com o marcador de invalidação ou com o cache fora
     * @param blocos   blocos da custódia lembrada; vazio se o hash não existe ou tem o marcador
     */
    record LeituraDaOperacao(Optional<CustodiaLembrada> custodia, Optional<BlocosEmCache> blocos) {

        public static final LeituraDaOperacao VAZIA = new LeituraDaOperacao(Optional.empty(), Optional.empty());
    }

    /** Blocos lidos do DynamoDB numa versão do registro (epoch ms), para o hash da operação na custódia. */
    record GravacaoDeBlocos(ChaveDeOperacao chave, long versao, Map<Bloco, byte[]> blocos) {

        public GravacaoDeBlocos {
            Objects.requireNonNull(chave, "chave");
            blocos = Map.copyOf(blocos);
        }
    }

    /** Custódia vencedora de uma operação, na versão do registro (epoch ms) do seu bloco {@code operacao}. */
    record GravacaoDeCustodia(long idOperacao, Custodia custodia, long versao) {

        public GravacaoDeCustodia {
            Objects.requireNonNull(custodia, "custodia");
        }
    }
}
