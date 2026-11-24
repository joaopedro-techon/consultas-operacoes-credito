package br.com.consultas.operacoescredito.domain.filtro;

/** Operadores de comparação do {@code filter}; os de ordem só valem para números, decimais e datas. */
public enum OperadorComparacao {

    IGUAL(false),
    DIFERENTE(false),
    MENOR(true),
    MENOR_OU_IGUAL(true),
    MAIOR(true),
    MAIOR_OU_IGUAL(true),
    EM(false),
    FORA(false);

    private final boolean deOrdem;

    OperadorComparacao(boolean deOrdem) {
        this.deOrdem = deOrdem;
    }

    /** {@code true} para os operadores que dependem de ordem ({@code <}, {@code <=}, {@code >}, {@code >=}). */
    public boolean deOrdem() {
        return deOrdem;
    }

    /** {@code true} quando ausência de valor atende ao operador ({@code !=} e {@code =out=}). */
    boolean aceitaAusencia() {
        return this == DIFERENTE || this == FORA;
    }
}
