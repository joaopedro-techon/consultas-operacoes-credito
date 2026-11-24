package br.com.consultas.operacoescredito.adapters.out.cache;

import java.util.Collection;
import java.util.Map;

/**
 * Cache por operação e bloco da visão consolidada (ADR-0002: a fonte primária não usa cache).
 * O valor é o {@code texto_detalhe} (JSON em GZIP) exatamente como veio do DynamoDB.
 * <p>
 * É só uma otimização (fail-open): nenhum método lança exceção por falha do cache; falha, lentidão ou
 * circuit breaker aberto equivalem a "nada em cache" na leitura e a "nada gravado" na escrita.
 */
public interface CacheDeBlocos {

    /**
     * Uma leitura de todas as chaves (dividida em {@code MGET}s menores em pipeline).
     *
     * @return {@code texto_detalhe} dos blocos em cache; chaves ausentes não aparecem no mapa
     */
    Map<ChaveDeBloco, byte[]> buscar(Collection<ChaveDeBloco> chaves);

    /** Grava os blocos com o TTL configurado, em best-effort: pode voltar antes de a gravação terminar. */
    void gravar(Map<ChaveDeBloco, byte[]> blocos);
}
