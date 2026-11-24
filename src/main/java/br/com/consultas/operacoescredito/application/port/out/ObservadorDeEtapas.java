package br.com.consultas.operacoescredito.application.port.out;

import java.util.function.Supplier;

/**
 * Mede uma etapa interna da consulta (span e timer) sem a camada de aplicação depender de Micrometer.
 * A implementação fica em {@code config.observabilidade}.
 */
public interface ObservadorDeEtapas {

    /** Executa o passo sem medir. */
    ObservadorDeEtapas NENHUM = new ObservadorDeEtapas() {
        @Override
        public <T> T observar(String etapa, Supplier<T> passo) {
            return passo.get();
        }
    };

    <T> T observar(String etapa, Supplier<T> passo);
}
