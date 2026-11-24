package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tipo de um campo do trancode e a conversão do texto (já sem brancos) no valor que a visão
 * consolidada produz para o mesmo campo: números inteiros como {@code Integer}/{@code Long},
 * decimais como {@code BigDecimal} com a escala das casas implícitas, datas como string ISO.
 */
enum TipoCampoTrancode {

    NUMERO {
        @Override
        Object converter(String valor, String formato) {
            var numero = Long.parseLong(valor.strip());
            return numero == (int) numero ? Integer.valueOf((int) numero) : Long.valueOf(numero);
        }
    },
    DECIMAL {
        @Override
        Object converter(String valor, String formato) {
            return new BigDecimal(new BigInteger(valor.strip()), casasDecimais(formato));
        }

        @Override
        void validarFormato(String formato) {
            if (casasDecimais(formato) < 0) {
                throw new IllegalArgumentException("casas decimais negativas");
            }
        }
    },
    DATA {
        @Override
        Object converter(String valor, String formato) {
            return LocalDate.parse(valor, formatador(formato)).format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        @Override
        void validarFormato(String formato) {
            formatador(formato);
        }
    },
    DATA_HORA {
        @Override
        Object converter(String valor, String formato) {
            return LocalDateTime.parse(valor, formatador(formato)).format(ISO_DATA_HORA);
        }

        @Override
        void validarFormato(String formato) {
            formatador(formato);
        }
    },
    TEXTO {
        @Override
        Object converter(String valor, String formato) {
            return valor.strip();
        }
    },
    BOOLEANO {
        @Override
        Object converter(String valor, String formato) {
            var caracteres = formato == null ? BOOLEANO_PADRAO : formato;
            if (valor.equals(caracteres.substring(0, 1))) {
                return Boolean.TRUE;
            }
            if (valor.equals(caracteres.substring(1, 2))) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("esperado um de '" + caracteres + "'");
        }

        @Override
        void validarFormato(String formato) {
            if (formato != null && formato.length() != 2) {
                throw new IllegalArgumentException("use dois caracteres: verdadeiro e falso (ex.: SN)");
            }
        }
    };

    private static final String BOOLEANO_PADRAO = "SN";
    /** Mesmo formato das datas-hora da visão consolidada (sempre com segundos). */
    private static final DateTimeFormatter ISO_DATA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Map<String, DateTimeFormatter> FORMATADORES = new ConcurrentHashMap<>();

    /** Converte o texto do campo, que não está em branco. */
    abstract Object converter(String valor, String formato);

    /** Valida o {@code formato} do layout na carga, para falhar na subida e não na consulta. */
    void validarFormato(String formato) {
    }

    String nome() {
        return name().toLowerCase(Locale.ROOT);
    }

    private static int casasDecimais(String formato) {
        if (formato == null) {
            throw new IllegalArgumentException("decimal exige formato com as casas implícitas (ex.: 2)");
        }
        return Integer.parseInt(formato.strip());
    }

    private static DateTimeFormatter formatador(String formato) {
        if (formato == null) {
            throw new IllegalArgumentException("data exige formato (ex.: yyyy-MM-dd)");
        }
        return FORMATADORES.computeIfAbsent(formato, DateTimeFormatter::ofPattern);
    }
}
