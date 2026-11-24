package br.com.consultas.operacoescredito.application.port.out;

import java.time.Duration;
import java.util.Objects;

/**
 * A cota de chamadas por segundo da fonte primária (do mainframe inteiro, dividida entre as instâncias da API) se
 * esgotou: a consulta é recusada na hora, sem esperar e sem chamar o mainframe (ADR-0015). Vira 429 com
 * {@code Retry-After}; não é uma falha da dependência (503).
 */
public class CotaDaFonteEsgotadaException extends FalhaNaFonteDeOperacoesException {

    private final Duration tenteNovamenteEm;

    public CotaDaFonteEsgotadaException(String mensagem, Duration tenteNovamenteEm, Throwable causa) {
        super(mensagem, causa);
        this.tenteNovamenteEm = Objects.requireNonNull(tenteNovamenteEm, "tenteNovamenteEm");
    }

    /** Quando vale a pena tentar de novo (vira o header {@code Retry-After}). */
    public Duration tenteNovamenteEm() {
        return tenteNovamenteEm;
    }
}
