package br.com.consultas.operacoescredito.config.observabilidade;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Hits e misses do cache por bloco na requisição em andamento, para o evento de consumo (a métrica
 * {@code consultas.cache} soma todas as requisições). Aberto e fechado pela borda HTTP; o adapter do cache só
 * registra, sem conhecer a web. Vive na thread da requisição e chega às threads filhas pela propagação de
 * contexto ({@link CacheDaRequisicaoThreadLocalAccessor}): todas compartilham a mesma contagem, segura para
 * escrita concorrente.
 * <p>
 * Fora de uma requisição (sem {@link #iniciar()}), registrar não faz nada.
 */
public final class CacheDaRequisicao {

    private static final ThreadLocal<Contagem> ATUAL = new ThreadLocal<>();

    private CacheDaRequisicao() {
    }

    /** Começa a contar na thread atual e devolve a contagem, para ser lida ao final da requisição. */
    public static Contagem iniciar() {
        var contagem = new Contagem();
        ATUAL.set(contagem);
        return contagem;
    }

    /** Remove a contagem da thread atual (fim da requisição). */
    public static void encerrar() {
        ATUAL.remove();
    }

    public static void hit(String bloco) {
        var contagem = ATUAL.get();
        if (contagem != null) {
            contagem.hits.computeIfAbsent(bloco, b -> new LongAdder()).increment();
        }
    }

    public static void miss(String bloco) {
        var contagem = ATUAL.get();
        if (contagem != null) {
            contagem.misses.computeIfAbsent(bloco, b -> new LongAdder()).increment();
        }
    }

    static Contagem atual() {
        return ATUAL.get();
    }

    static void definir(Contagem contagem) {
        if (contagem == null) {
            ATUAL.remove();
        } else {
            ATUAL.set(contagem);
        }
    }

    /** Contagem de uma requisição, por nome canônico do bloco. */
    public static final class Contagem {

        private final Map<String, LongAdder> hits = new ConcurrentHashMap<>();
        private final Map<String, LongAdder> misses = new ConcurrentHashMap<>();

        /** Blocos encontrados no cache e quantas vezes (uma por operação), em ordem alfabética. */
        public Map<String, Long> hits() {
            return copia(hits);
        }

        /** Blocos procurados e ausentes no cache (ou com o cache fora), em ordem alfabética. */
        public Map<String, Long> misses() {
            return copia(misses);
        }

        private static Map<String, Long> copia(Map<String, LongAdder> contadores) {
            var copia = new TreeMap<String, Long>();
            contadores.forEach((bloco, contador) -> copia.put(bloco, contador.sum()));
            return copia;
        }
    }
}
