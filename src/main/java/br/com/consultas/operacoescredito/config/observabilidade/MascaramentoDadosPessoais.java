package br.com.consultas.operacoescredito.config.observabilidade;

import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mascara CPF/CNPJ e conta em textos de log (LGPD). Três regras, aplicadas a todo valor textual:
 * <ol>
 *     <li>campo cujo nome é {@code cpf_cnpj}, {@code cpf}, {@code cnpj} ou {@code conta}: valor inteiro mascarado;</li>
 *     <li>pares {@code chave=valor}, {@code chave==valor} (RSQL) e {@code "chave":"valor"} (JSON) com essas chaves;</li>
 *     <li>CPF ou CNPJ (numérico ou alfanumérico) soltos no texto, com ou sem pontuação, desde que o dígito
 *     verificador seja válido.</li>
 * </ol>
 * CPF/CNPJ mantêm os 3 primeiros e os 2 últimos caracteres (sem a pontuação); conta mantém só os 4 últimos.
 */
public final class MascaramentoDadosPessoais {

    private static final Set<String> CAMPOS_DOCUMENTO = Set.of("cpf_cnpj", "cpf", "cnpj");
    private static final Set<String> CAMPOS_CONTA = Set.of("conta");
    /** Identificadores que nunca são dados pessoais, ainda que pareçam um CPF. */
    private static final Set<String> CAMPOS_NAO_PESSOAIS = Set.of("id_operacao", "correlation_id");

    private static final Pattern PAR_CHAVE_VALOR = Pattern.compile(
            "(?<![A-Za-z0-9])(cpf_cnpj|cpf|cnpj|conta)(\"?\\s*(?:=[a-z]*=|=|:)\\s*\"?)([A-Za-z0-9./-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENTO_SOLTO = Pattern.compile(
            "(?<![A-Za-z0-9])(\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}"
                    + "|[A-Z0-9]{2}\\.?[A-Z0-9]{3}\\.?[A-Z0-9]{3}/?[A-Z0-9]{4}-?\\d{2})(?![A-Za-z0-9])");
    private static final Pattern NAO_ALFANUMERICO = Pattern.compile("[^A-Za-z0-9]");

    private MascaramentoDadosPessoais() {
    }

    /** Mascara o valor de um campo de log, considerando o nome do campo. */
    public static String mascarar(String campo, String valor) {
        if (valor == null || valor.isEmpty()) {
            return valor;
        }
        if (campo != null && CAMPOS_NAO_PESSOAIS.contains(campo)) {
            return valor;
        }
        if (campo != null && CAMPOS_DOCUMENTO.contains(campo)) {
            return mascararDocumento(valor);
        }
        if (campo != null && CAMPOS_CONTA.contains(campo)) {
            return mascararConta(valor);
        }
        return mascarar(valor);
    }

    /** Mascara CPF/CNPJ e conta presentes num texto livre. */
    public static String mascarar(String texto) {
        if (texto == null || texto.isEmpty() || texto.chars().noneMatch(Character::isDigit)) {
            return texto;
        }
        var semPares = substituir(PAR_CHAVE_VALOR.matcher(texto), m -> m.group(1) + m.group(2)
                + (CAMPOS_CONTA.contains(m.group(1).toLowerCase()) ? mascararConta(m.group(3)) : mascararDocumento(m.group(3))));
        return substituir(DOCUMENTO_SOLTO.matcher(semPares),
                m -> documentoValido(m.group(1)) ? mascararDocumento(semPontuacao(m.group(1))) : m.group(1));
    }

    private static String substituir(Matcher matcher, Function<Matcher, String> substituto) {
        var resultado = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(resultado, Matcher.quoteReplacement(substituto.apply(matcher)));
        }
        matcher.appendTail(resultado);
        return resultado.toString();
    }

    static String mascararDocumento(String documento) {
        if (documento.length() <= 5) {
            return "*".repeat(documento.length());
        }
        return documento.substring(0, 3) + "*".repeat(documento.length() - 5) + documento.substring(documento.length() - 2);
    }

    static String mascararConta(String conta) {
        if (conta.length() <= 4) {
            return "*".repeat(conta.length());
        }
        return "*".repeat(conta.length() - 4) + conta.substring(conta.length() - 4);
    }

    private static String semPontuacao(String documento) {
        return NAO_ALFANUMERICO.matcher(documento).replaceAll("");
    }

    private static boolean documentoValido(String candidato) {
        var limpo = semPontuacao(candidato);
        return switch (limpo.length()) {
            case 11 -> cpfValido(limpo);
            case 14 -> cnpjValido(limpo);
            default -> false;
        };
    }

    private static boolean cpfValido(String cpf) {
        if (!cpf.chars().allMatch(Character::isDigit) || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return digitoCpf(cpf, 9) == cpf.charAt(9) - '0' && digitoCpf(cpf, 10) == cpf.charAt(10) - '0';
    }

    private static int digitoCpf(String cpf, int tamanho) {
        var soma = 0;
        for (var i = 0; i < tamanho; i++) {
            soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
        }
        var resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    /** CNPJ numérico ou alfanumérico: cada caractere vale seu código ASCII menos 48. */
    private static boolean cnpjValido(String cnpj) {
        if (cnpj.chars().distinct().count() == 1) {
            return false;
        }
        return digitoCnpj(cnpj, 12) == cnpj.charAt(12) - '0' && digitoCnpj(cnpj, 13) == cnpj.charAt(13) - '0';
    }

    private static int digitoCnpj(String cnpj, int tamanho) {
        var soma = 0;
        var peso = 2;
        for (var i = tamanho - 1; i >= 0; i--) {
            soma += (cnpj.charAt(i) - '0') * peso;
            peso = peso == 9 ? 2 : peso + 1;
        }
        var resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
