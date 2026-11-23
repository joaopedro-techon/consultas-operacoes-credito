package br.com.consultas.operacoescredito.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Uma operação de crédito como devolvida pela consulta: um conteúdo por bloco pedido.
 * O conteúdo é o JSON do bloco já desserializado ({@code Map<String, Object>} para blocos-objeto,
 * {@code List<Map<String, Object>>} para blocos-lista), sem classe Java por campo (ADR-0005).
 * Um bloco pedido mas ausente na fonte fica com valor {@code null}.
 */
public record OperacaoConsultada(Map<Bloco, Object> blocos) {

    public OperacaoConsultada {
        Objects.requireNonNull(blocos, "blocos");
        blocos = Collections.unmodifiableMap(new EnumMap<>(blocos));
    }
}
