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
 * Antes do HMAC, CPF/CNPJ e conta escritos com pontuação ({@code 529.982.247-25}, {@code 12.ABC.345/01DE-35}) perdem
 * {@code .}, {@code -} e {@code /}, para darem o mesmo HMAC da forma sem pontuação (a do {@code cpf_cnpj_hmac}).
 * <p>
 * No texto do {@code filter} (RSQL), troca o argumento de toda comparação cujo seletor termina em
 * {@code cpf_cnpj}, {@code cpf}, {@code cnpj} ou {@code conta} (inclusive cada item de {@code =in=(...)}) e,
 * por garantia, qualquer CPF/CNPJ solto, com ou sem pontuação, com dígito verificador válido.
 */
final class PseudonimizadorHmac {

    /** Menor segredo aceito: 256 bits, o tamanho do bloco de saída do SHA-256. */
    static final int TAMANHO_MINIMO_SEGREDO = 32;

    private static final String ALGORITMO = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern COMPARACAO_PESSOAL = Pattern.compile(
            "(?<![A-Za-z0-9_.])((?:[A-Za-z0-9_]+\\.)*(?:cpf_cnpj|cpf|cnpj|conta))"
                    + "(\\s*(?:==|!=|=[A-Za-z]*=|<=|>=|<|>)\\s*)"
                    + "(\\([^)]*\\)|'[^']*'|\"[^\"]*\"|[^;,()\\s]+)",
            Pattern.CASE_INSENSITIVE);
    /** CPF ({@code 000.000.000-00}) ou CNPJ ({@code 00.000.000/0000-00}, alfanumérico), pontuação opcional. */
    private static final Pattern DOCUMENTO_SOLTO = Pattern.compile(
            "(?<![A-Za-z0-9])(?:\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}"
                    + "|[0-9A-Z]{2}\\.?[0-9A-Z]{3}\\.?[0-9A-Z]{3}/?[0-9A-Z]{4}-?\\d{2})(?![A-Za-z0-9])");
    private static final Pattern PONTUACAO = Pattern.compile("[./-]");
    private static final Pattern DIGITOS = Pattern.compile("\\d+");

    private final Mac prototipo;

    /**
     * @throws IllegalArgumentException segredo com menos de {@value #TAMANHO_MINIMO_SEGREDO} bytes
     */
    PseudonimizadorHmac(byte[] segredo) {
        Objects.requireNonNull(segredo, "segredo");
        if (segredo.length < TAMANHO_MINIMO_SEGREDO) {
            throw new IllegalArgumentException("O segredo do HMAC deve ter pelo menos " + TAMANHO_MINIMO_SEGREDO
                    + " bytes; tem " + segredo.length);
        }
        try {
            prototipo = Mac.getInstance(ALGORITMO);
            prototipo.init(new SecretKeySpec(segredo, ALGORITMO));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    /** HMAC-SHA256 do valor exatamente como veio, em hex minúsculo; {@code null} se o valor for {@code null}. */
    String hmac(String valor) {
        if (valor == null) {
            return null;
        }
        return HEX.formatHex(novoMac().doFinal(valor.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * HMAC de um CPF/CNPJ ou conta: sem a pontuação quando o que sobra é um CPF/CNPJ válido ou só dígitos; como veio
     * nos demais casos. {@code null} se o valor for {@code null}.
     */
    String hmacDeDadoPessoal(String valor) {
        if (valor == null) {
            return null;
        }
        var semPontuacao = PONTUACAO.matcher(valor).replaceAll("");
        var normalizado = CpfCnpj.valido(semPontuacao) || DIGITOS.matcher(semPontuacao).matches() ? semPontuacao : valor;
        return hmac(normalizado);
    }

    /** O filtro com CPF/CNPJ e conta trocados pelo HMAC; {@code null} se o filtro for {@code null}. */
    String filtro(String filtro) {
        if (filtro == null) {
            return null;
        }
        var semComparacoes = substituir(COMPARACAO_PESSOAL.matcher(filtro),
                m -> m.group(1) + m.group(2) + argumento(m.group(3)));
        return substituir(DOCUMENTO_SOLTO.matcher(semComparacoes), m -> {
            var documento = PONTUACAO.matcher(m.group()).replaceAll("");
            return CpfCnpj.valido(documento) ? hmac(documento) : m.group();
        });
    }

    private String argumento(String argumento) {
        if (argumento.startsWith("(")) {
            var itens = argumento.substring(1, argumento.length() - 1).split(",", -1);
            var resultado = new StringBuilder("(");
            for (int i = 0; i < itens.length; i++) {
                resultado.append(i == 0 ? "" : ",").append(hmacDeDadoPessoal(semAspas(itens[i].strip())));
            }
            return resultado.append(')').toString();
        }
        return hmacDeDadoPessoal(semAspas(argumento));
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
