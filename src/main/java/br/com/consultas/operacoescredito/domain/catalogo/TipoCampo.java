package br.com.consultas.operacoescredito.domain.catalogo;

/**
 * Tipo de um campo de bloco, declarado no catálogo de campos (ADR-0005). Dita como o valor do
 * {@code Map} do bloco é comparado em filtros: números e decimais numericamente ({@code BigDecimal}),
 * datas e datas-hora cronologicamente (string no formato da origem), texto e booleano por igualdade.
 */
public enum TipoCampo {

    /** Inteiro (ex.: {@code id_operacao}, {@code numero_parcela_amortizacao}). */
    NUMERO,
    /** Decimal, sempre {@code BigDecimal} (valores monetários e taxas). */
    DECIMAL,
    /** Data {@code yyyy-MM-dd}. */
    DATA,
    /** Data-hora sem fuso {@code yyyy-MM-ddTHH:mm:ss}. */
    DATA_HORA,
    TEXTO,
    BOOLEANO,
    /** Objeto aninhado; seus campos são declarados no próprio campo. */
    OBJETO,
    /** Lista de objetos aninhados; os campos de cada item são declarados no próprio campo. */
    LISTA;

    /** {@code true} para {@link #OBJETO} e {@link #LISTA}, que têm subcampos. */
    public boolean composto() {
        return this == OBJETO || this == LISTA;
    }
}
