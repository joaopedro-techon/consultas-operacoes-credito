package br.com.consultas.operacoescredito.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Uma operação de crédito como devolvida pela consulta: um conteúdo por bloco pedido.
 * O conteúdo é o JSON do bloco já desserializado ({@code Map<String, Object>} para blocos-objeto,
 * {@code List<Map<String, Object>>} para blocos-lista), sem classe Java por campo (ADR-0005).
 * Um bloco pedido mas ausente na fonte, ou que a fonte primária não conseguiu ler (ADR-0013), fica com valor
 * {@code null}: é um bloco indisponível.
 */
public record OperacaoConsultada(Map<Bloco, Object> blocos) {

    public OperacaoConsultada {
        Objects.requireNonNull(blocos, "blocos");
        blocos = Collections.unmodifiableMap(new EnumMap<>(blocos));
    }

    /**
     * Operação encontrada na {@code custodia}: o bloco {@code operacao} (que precisa estar presente e ser um objeto)
     * ganha o campo {@value Custodia#CAMPO}, preenchido pela aplicação e não pela fonte (ADR-0012).
     */
    public static OperacaoConsultada naCustodia(Custodia custodia, Map<Bloco, Object> blocos) {
        Objects.requireNonNull(custodia, "custodia");
        var copia = new EnumMap<Bloco, Object>(Bloco.class);
        copia.putAll(blocos);
        if (!(copia.get(Bloco.OPERACAO) instanceof Map<?, ?> operacao)) {
            throw new IllegalArgumentException("Operação sem bloco operacao (objeto) não tem custódia");
        }
        var comCustodia = new LinkedHashMap<Object, Object>(operacao);
        comCustodia.put(Custodia.CAMPO, custodia.codigo());
        copia.put(Bloco.OPERACAO, comCustodia);
        return new OperacaoConsultada(copia);
    }

    /** Custódia registrada no bloco {@code operacao}, se houver. */
    public Optional<Custodia> custodia() {
        return blocos.get(Bloco.OPERACAO) instanceof Map<?, ?> operacao
                && operacao.get(Custodia.CAMPO) instanceof String codigo
                ? Custodia.porCodigo(codigo)
                : Optional.empty();
    }

    /** Blocos pedidos que a fonte ainda não tem para esta operação, na ordem canônica. */
    public Set<Bloco> blocosIndisponiveis() {
        var indisponiveis = EnumSet.noneOf(Bloco.class);
        blocos.forEach((bloco, conteudo) -> {
            if (conteudo == null) {
                indisponiveis.add(bloco);
            }
        });
        return indisponiveis;
    }
}
