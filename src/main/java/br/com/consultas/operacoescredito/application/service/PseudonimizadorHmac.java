package br.com.consultas.operacoescredito.application.service;

import br.com.consultas.operacoescredito.domain.CpfCnpj;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Troca CPF/CNPJ e conta pelo HMAC-SHA256 (hex minúsculo) com o segredo da aplicação: o mesmo documento dá sempre
 * o mesmo valor, o que permite contar e cruzar consumo por cliente sem guardar o dado.
 * <p>
 * No texto do {@code filter} (RSQL), troca o argumento de toda comparação cujo seletor termina em
 * {@code cpf_cnpj}, {@code cpf}, {@code cnpj} ou {@code conta} (inclusive cada item de {@code =in=(...)}) e,
 * por garantia, qualquer CPF/CNPJ solto com dígito verificador válido.
 */
final class PseudonimizadorHmac {

    private static final String ALGORITMO = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern COMPARACAO_PESSOAL = Pattern.compile(
            "(?<![A-Za-z0-9_.])((?:[A-Za-z0-9_]+\\.)*(?:cpf_cnpj|cpf|cnpj|conta))"
                    + "(\\s*(?:==|!=|=[A-Za-z]*=|<=|>=|<|>)\\s*)"
                    + "(\\([^)]*\\)|'[^']*'|\"[^\"]*\"|[^;,()\\s]+)");
    private static final Pattern DOCUMENTO_SOLTO = Pattern.compile("(?<![A-Za-z0-9])[0-9A-Z]{11,14}(?![A-Za-z0-9])");

    private final Mac prototipo;

    PseudonimizadorHmac(byte[] segredo) {
        Objects.requireNonNull(segredo, "segredo");
        if (segredo.length == 0) {
            throw new IllegalArgumentException("O segredo do HMAC não pode ser vazio");
        }
        try {
            prototipo = Mac.getInstance(ALGORITMO);
            prototipo.init(new SecretKeySpec(segredo, ALGORITMO));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    /** HMAC-SHA256 do valor em hex minúsculo; {@code null} se o valor for {@code null}. */
    String hmac(String valor) {
        if (valor == null) {
            return null;
        }
        return HEX.formatHex(novoMac().doFinal(valor.getBytes(StandardCharsets.UTF_8)));
    }

    /** O filtro com CPF/CNPJ e conta trocados pelo HMAC; {@code null} se o filtro for {@code null}. */
    String filtro(String filtro) {
        if (filtro == null) {
            return null;
        }
        var semComparacoes = substituir(COMPARACAO_PESSOAL.matcher(filtro),
                m -> m.group(1) + m.group(2) + argumento(m.group(3)));
        return substituir(DOCUMENTO_SOLTO.matcher(semComparacoes),
                m -> CpfCnpj.valido(m.group()) ? hmac(m.group()) : m.group());
    }

    private String argumento(String argumento) {
        if (argumento.startsWith("(")) {
            var itens = argumento.substring(1, argumento.length() - 1).split(",", -1);
            var resultado = new StringBuilder("(");
            for (int i = 0; i < itens.length; i++) {
                resultado.append(i == 0 ? "" : ",").append(hmac(semAspas(itens[i].strip())));
            }
            return resultado.append(')').toString();
        }
        return hmac(semAspas(argumento));
    }

    private static String semAspas(String valor) {
        var aspas = valor.length() >= 2 && (valor.startsWith("'") && valor.endsWith("'")
                || valor.startsWith("\"") && valor.endsWith("\""));
        return aspas ? valor.substring(1, valor.length() - 1) : valor;
    }

    private Mac novoMac() {
        try {
            return (Mac) prototipo.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("HMAC-SHA256 sem suporte a clone", e);
        }
    }

    private static String substituir(Matcher matcher, Function<Matcher, String> substituto) {
        var resultado = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(resultado, Matcher.quoteReplacement(substituto.apply(matcher)));
        }
        matcher.appendTail(resultado);
        return resultado.toString();
    }
}
