package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.Custodia;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Cache versionado da visão consolidada: um hash por operação e custódia com os blocos e a versão do registro de
 * cada um, e a custódia lembrada de cada operação, também com versão. Os blocos são o {@code texto_detalhe} (JSON em
 * GZIP) exatamente como veio do DynamoDB. O formato do hash segue a regra de versão dos blocos (ADR-0018, ADR-0019):
 * com versão única, todos os blocos de um hash são de uma versão só; com versão por bloco, cada um tem a sua.
 * <p>
 * Uma gravação nunca troca uma versão do cache por uma menor, e um bloco (ou hash) com o marcador de invalidação
 * conta como ausente. É só uma otimização (fail-open): nenhum método lança exceção por falha do cache; falha,
 * lentidão ou circuit breaker aberto equivalem a "nada em cache" na leitura e a "nada gravado" na escrita.
 */
public interface CacheDeBlocos {

    /**
     * Consulta OPERACAO, numa só ida ao Valkey: a custódia lembrada da operação e os blocos pedidos que o cache
     * tem nela.
     */
    LeituraDaOperacao buscarOperacao(long idOperacao, Set<Bloco> blocos);

    /**
     * Consultas CLIENTE e CONTA, de operações com custódia já conhecida (do GSI): os blocos pedidos que o cache tem.
     * Com uma versão exigida, só os blocos <b>nessa versão</b>; sem ela, os blocos em qualquer versão. Operação sem
     * nenhum bloco aceito não aparece.
     */
    Map<ChaveDeOperacao, BlocosEmCache> buscar(Map<ChaveDeOperacao, OptionalLong> versoesExigidas, Set<Bloco> blocos);

    /** Grava blocos e custódias com a regra de versão, em best-effort: pode voltar antes de a gravação terminar. */
    void gravar(Collection<GravacaoDeBlocos> blocos, Collection<GravacaoDeCustodia> custodias);

    /**
     * Um bloco e a sua versão do registro.
     *
     * @param versao       versão do registro, em epoch ms
     * @param textoDetalhe {@code texto_detalhe} do bloco, como no DynamoDB
     */
    record BlocoVersionado(long versao, byte[] textoDetalhe) {

        public BlocoVersionado {
            Objects.requireNonNull(textoDetalhe, "textoDetalhe");
        }
    }

    /**
     * Blocos de uma operação em cache.
     *
     * @param blocos os blocos pedidos que estavam no hash, com a versão de cada um; os ausentes não aparecem
     */
    record BlocosEmCache(Map<Bloco, BlocoVersionado> blocos) {

        public BlocosEmCache {
            blocos = Map.copyOf(blocos);
        }

        /** Blocos todos numa mesma versão (hash de versão única). */
        public static BlocosEmCache naVersao(long versao, Map<Bloco, byte[]> textos) {
            return new BlocosEmCache(versionar(versao, textos));
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

    /**
     * Blocos lidos do DynamoDB, cada um com a sua versão do registro (epoch ms), para o hash da operação na custódia.
     * Com versão única, o hash só é gravado se todos os blocos tiverem a mesma versão.
     */
    record GravacaoDeBlocos(ChaveDeOperacao chave, Map<Bloco, BlocoVersionado> blocos) {

        public GravacaoDeBlocos {
            Objects.requireNonNull(chave, "chave");
            blocos = Map.copyOf(blocos);
        }

        /** Blocos todos numa mesma versão. */
        public static GravacaoDeBlocos naVersao(ChaveDeOperacao chave, long versao, Map<Bloco, byte[]> textos) {
            return new GravacaoDeBlocos(chave, versionar(versao, textos));
        }

        /** A versão comum a todos os blocos; vazia se divergem ou se não há blocos. */
        public OptionalLong versaoUnica() {
            var versoes = blocos.values().stream().mapToLong(BlocoVersionado::versao).distinct().toArray();
            return versoes.length == 1 ? OptionalLong.of(versoes[0]) : OptionalLong.empty();
        }
    }

    /** Custódia vencedora de uma operação, na versão do registro (epoch ms) do seu bloco {@code operacao}. */
    record GravacaoDeCustodia(long idOperacao, Custodia custodia, long versao) {

        public GravacaoDeCustodia {
            Objects.requireNonNull(custodia, "custodia");
        }
    }

    private static Map<Bloco, BlocoVersionado> versionar(long versao, Map<Bloco, byte[]> textos) {
        var blocos = new EnumMap<Bloco, BlocoVersionado>(Bloco.class);
        textos.forEach((bloco, texto) -> blocos.put(bloco, new BlocoVersionado(versao, texto)));
        return blocos;
    }
}
