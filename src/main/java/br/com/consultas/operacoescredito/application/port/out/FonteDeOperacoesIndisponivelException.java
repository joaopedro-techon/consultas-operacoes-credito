package br.com.consultas.operacoescredito.application.port.out;

import java.time.Duration;
import java.util.Objects;

/**
 * A aplicação recusou a chamada à fonte de operações para se proteger (circuit breaker aberto, bulkhead cheio
 * ou rate limiter interno), sem chegar a chamá-la. O consumidor deve tentar de novo depois de
 * {@link #tenteNovamenteEm()}.
 */
public class FonteDeOperacoesIndisponivelException extends FalhaNaFonteDeOperacoesException {

    private final Duration tenteNovamenteEm;

    public FonteDeOperacoesIndisponivelException(String mensagem, Duration tenteNovamenteEm, Throwable causa) {
        super(mensagem, causa);
        this.tenteNovamenteEm = Objects.requireNonNull(tenteNovamenteEm, "tenteNovamenteEm");
    }

    /** Quando vale a pena tentar de novo (vira o header {@code Retry-After}). */
    public Duration tenteNovamenteEm() {
        return tenteNovamenteEm;
    }
}
