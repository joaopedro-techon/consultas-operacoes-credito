package br.com.consultas.operacoescredito.application.port.out;

/**
 * Publica eventos de consumo em best-effort (ADR-0004): {@link #publicar} só enfileira, nunca bloqueia nem lança
 * exceção; se não houver como publicar, o evento é descartado e contado.
 */
@FunctionalInterface
public interface PublicadorDeEventosDeConsumo {

    void publicar(EventoDeConsumo evento);
}
