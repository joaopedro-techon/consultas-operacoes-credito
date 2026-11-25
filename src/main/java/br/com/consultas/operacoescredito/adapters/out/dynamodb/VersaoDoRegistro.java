package br.com.consultas.operacoescredito.adapters.out.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Versão do registro de um item da visão consolidada: o {@code codigo_identificador_carga} como veio e, se ele é
 * uma data-hora ISO-8601, o instante em epoch ms, que é como a versão vai para o cache (ADR-0018). Sem fuso, a
 * data-hora é lida como UTC; os dois escritores gravam sempre no mesmo formato.
 *
 * @param bruto   valor do atributo; {@code null} quando o item não o tem (ou quando a versão veio do cache)
 * @param epochMs instante em epoch ms; vazio quando o valor está ausente ou fora do formato
 */
record VersaoDoRegistro(String bruto, OptionalLong epochMs) {

    static final VersaoDoRegistro AUSENTE = new VersaoDoRegistro(null, OptionalLong.empty());

    VersaoDoRegistro {
        Objects.requireNonNull(epochMs, "epochMs");
    }

    static VersaoDoRegistro de(AttributeValue valor) {
        if (valor == null || valor.s() == null) {
            return AUSENTE;
        }
        return new VersaoDoRegistro(valor.s(), epoch(valor.s()));
    }

    /** Versão guardada no cache, que só guarda versões em epoch ms. */
    static VersaoDoRegistro doCache(long epochMs) {
        return new VersaoDoRegistro(null, OptionalLong.of(epochMs));
    }

    /**
     * Mesma versão: o mesmo instante, se alguma das duas o tem; senão, o mesmo valor bruto (inclusive ambas
     * ausentes).
     */
    boolean mesma(VersaoDoRegistro outra) {
        if (epochMs.isPresent() || outra.epochMs.isPresent()) {
            return epochMs.equals(outra.epochMs);
        }
        return Objects.equals(bruto, outra.bruto);
    }

    private static OptionalLong epoch(String texto) {
        try {
            var lido = DateTimeFormatter.ISO_DATE_TIME.parseBest(texto, OffsetDateTime::from, LocalDateTime::from);
            var instante = lido instanceof OffsetDateTime comFuso
                    ? comFuso.toInstant()
                    : ((LocalDateTime) lido).toInstant(ZoneOffset.UTC);
            return OptionalLong.of(instante.toEpochMilli());
        } catch (DateTimeException e) {
            return OptionalLong.empty();
        }
    }
}
