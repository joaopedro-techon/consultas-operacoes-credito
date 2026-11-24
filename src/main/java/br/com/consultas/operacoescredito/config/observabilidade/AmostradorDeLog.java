package br.com.consultas.operacoescredito.config.observabilidade;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limita logs repetitivos: no máximo um por chave a cada {@code intervalo}. Sob carga, um circuito aberto recusa
 * milhares de chamadas por segundo; sem amostragem, cada recusa seria uma linha de log.
 */
final class AmostradorDeLog {

    private final Duration intervalo;
    private final Clock relogio;
    private final ConcurrentHashMap<String, Janela> janelas = new ConcurrentHashMap<>();

    AmostradorDeLog(Duration intervalo, Clock relogio) {
        this.intervalo = intervalo;
        this.relogio = relogio;
    }

    /**
     * @return vazio se o log deve ser suprimido; senão, quantos foram suprimidos desde o último log desta chave
     */
    OptionalLong permitir(String chave) {
        var janela = janelas.computeIfAbsent(chave, k -> new Janela());
        var agora = relogio.millis();
        var ultimo = janela.ultimo.get();
        if (ultimo != Long.MIN_VALUE && agora - ultimo < intervalo.toMillis()) {
            janela.suprimidos.incrementAndGet();
            return OptionalLong.empty();
        }
        if (!janela.ultimo.compareAndSet(ultimo, agora)) {
            janela.suprimidos.incrementAndGet();
            return OptionalLong.empty();
        }
        return OptionalLong.of(janela.suprimidos.getAndSet(0));
    }

    private static final class Janela {
        private final AtomicLong ultimo = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong suprimidos = new AtomicLong();
    }
}
