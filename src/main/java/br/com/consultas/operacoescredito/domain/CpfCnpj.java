package br.com.consultas.operacoescredito.domain;

import java.util.regex.Pattern;

/**
 * Validação do documento do cliente: CPF (11 dígitos) ou CNPJ (14 caracteres, numérico ou alfanumérico).
 * No CNPJ alfanumérico as 12 primeiras posições aceitam {@code 0-9} e {@code A-Z} (maiúsculas) e os
 * dois DVs são dígitos; cada caractere vale o seu código ASCII menos 48 no cálculo do módulo 11.
 */
public final class CpfCnpj {

    private static final Pattern CPF = Pattern.compile("\\d{11}");
    private static final Pattern CNPJ = Pattern.compile("[0-9A-Z]{12}\\d{2}");
    private static final int[] PESOS_CNPJ = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private CpfCnpj() {
    }

    /** {@code true} quando {@code documento} é um CPF ou CNPJ bem formado e com DVs corretos. */
    public static boolean valido(String documento) {
        if (documento == null) {
            return false;
        }
        if (CPF.matcher(documento).matches()) {
            return !repetido(documento) && dvsConferem(documento, 9, CpfCnpj::pesoCpf);
        }
        if (CNPJ.matcher(documento).matches()) {
            return !repetido(documento) && dvsConferem(documento, 12, CpfCnpj::pesoCnpj);
        }
        return false;
    }

    /** Documentos com todos os caracteres iguais passam no módulo 11, mas não são válidos. */
    private static boolean repetido(String documento) {
        return documento.chars().allMatch(c -> c == documento.charAt(0));
    }

    private static boolean dvsConferem(String documento, int tamanhoBase, Peso peso) {
        for (int dv = 0; dv < 2; dv++) {
            int tamanho = tamanhoBase + dv;
            int soma = 0;
            for (int i = 0; i < tamanho; i++) {
                soma += (documento.charAt(i) - '0') * peso.de(i, tamanho);
            }
            int resto = soma % 11;
            int esperado = resto < 2 ? 0 : 11 - resto;
            if (documento.charAt(tamanho) - '0' != esperado) {
                return false;
            }
        }
        return true;
    }

    private static int pesoCpf(int posicao, int tamanho) {
        return tamanho + 1 - posicao;
    }

    private static int pesoCnpj(int posicao, int tamanho) {
        return PESOS_CNPJ[PESOS_CNPJ.length - tamanho + posicao];
    }

    @FunctionalInterface
    private interface Peso {
        int de(int posicao, int tamanho);
    }
}
