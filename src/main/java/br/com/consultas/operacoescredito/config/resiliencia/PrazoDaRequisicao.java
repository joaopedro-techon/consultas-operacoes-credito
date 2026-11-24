package br.com.consultas.operacoescredito.config.resiliencia;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Prazo da requisição em andamento (fail fast → 504), definido por tipo de consulta no início da requisição e
 * consultado pelos adapters de saída para não gastar mais do que resta: limitar a espera de cada chamada,
 * desistir de um retry que não cabe e abortar o fan-out. Vive na thread da requisição e chega às threads
 * filhas pela propagação de contexto ({@link PrazoDaRequisicaoThreadLocalAccessor}).
 * <p>
 * Sem prazo definido (fora de uma requisição HTTP), {@link #restante()} é vazio e nada é limitado.
 */
public final class PrazoDaRequisicao {

    private static final ThreadLocal<Long> LIMITE_NANOS = new ThreadLocal<>();

    private PrazoDaRequisicao() {
    }

    /** Começa a contar o prazo da requisição na thread atual. */
    public static void iniciar(Duration prazo) {
        Objects.requireNonNull(prazo, "prazo");
        LIMITE_NANOS.set(System.nanoTime() + prazo.toNanos());
    }

    /** Remove o prazo da thread atual (fim da requisição). */
    public static void encerrar() {
        LIMITE_NANOS.remove();
    }

    /**
     * Quanto resta do prazo; {@link Duration#ZERO} se já se esgotou, vazio se não há prazo.
     */
    public static Optional<Duration> restante() {
        var limite = LIMITE_NANOS.get();
        if (limite == null) {
            return Optional.empty();
        }
        var restante = limite - System.nanoTime();
        return Optional.of(restante > 0 ? Duration.ofNanos(restante) : Duration.ZERO);
    }

    /** {@code true} se há prazo e ele já se esgotou. */
    public static boolean esgotado() {
        return restante().map(Duration::isZero).orElse(false);
    }

    /** {@code true} se não há prazo ou se ainda resta pelo menos {@code tempo}. */
    public static boolean comporta(Duration tempo) {
        return restante().map(restante -> restante.compareTo(tempo) >= 0).orElse(true);
    }

    /** {@code valor} limitado ao que resta do prazo (no mínimo 1 ms, para não virar "sem timeout"). */
    public static Duration limitar(Duration valor) {
        return restante()
                .map(restante -> restante.compareTo(valor) < 0 ? restante : valor)
                .map(limitado -> limitado.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : limitado)
                .orElse(valor);
    }

    static Long limiteNanos() {
        return LIMITE_NANOS.get();
    }

    static void definirLimiteNanos(Long limite) {
        if (limite == null) {
            LIMITE_NANOS.remove();
        } else {
            LIMITE_NANOS.set(limite);
        }
    }
}
