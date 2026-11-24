package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Custodia;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Cache por operação e bloco da visão consolidada (ADR-0002).
 * O valor é o {@code texto_detalhe} (JSON em GZIP) exatamente como veio do DynamoDB. Na mesma leitura (um só
 * {@code MGET}) vêm também as custódias das operações (cache de custódia, ADR-0012), sem ida extra ao Valkey.
 * <p>
 * É só uma otimização (fail-open): nenhum método lança exceção por falha do cache; falha, lentidão ou
 * circuit breaker aberto equivalem a "nada em cache" na leitura e a "nada gravado" na escrita.
 */
public interface CacheDeBlocos {

    /**
     * Uma leitura de todas as chaves de bloco e das custódias pedidas (dividida em {@code MGET}s menores em
     * pipeline).
     *
     * @param custodiasDe operações cuja custódia também deve ser lida
     */
    LeituraDoCache buscar(Collection<ChaveDeBloco> chaves, Collection<Long> custodiasDe);

    /** Só blocos, sem custódias. */
    default Map<ChaveDeBloco, byte[]> buscar(Collection<ChaveDeBloco> chaves) {
        return buscar(chaves, List.of()).blocos();
    }

    /**
     * Grava os blocos (TTL do cache de blocos) e as custódias (TTL do cache de custódia), em best-effort: pode
     * voltar antes de a gravação terminar.
     */
    void gravar(Map<ChaveDeBloco, byte[]> blocos, Map<Long, Custodia> custodias);

    default void gravar(Map<ChaveDeBloco, byte[]> blocos) {
        gravar(blocos, Map.of());
    }

    /**
     * O que a leitura encontrou.
     *
     * @param blocos    {@code texto_detalhe} dos blocos em cache; chaves ausentes não aparecem
     * @param custodias custódia de cada operação encontrada no cache de custódia
     */
    record LeituraDoCache(Map<ChaveDeBloco, byte[]> blocos, Map<Long, Custodia> custodias) {

        public static final LeituraDoCache VAZIA = new LeituraDoCache(Map.of(), Map.of());

        public LeituraDoCache {
            blocos = Map.copyOf(blocos);
            custodias = Map.copyOf(custodias);
        }
    }
}
