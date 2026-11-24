package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CpfCnpj;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.TipoConsulta;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Header e query params da consulta. Valida a coerência entre o tipo de consulta e os parâmetros
 * e converte o pedido em {@link CriterioConsulta}.
 */
record ParametrosConsulta(String tipoConsulta, String idOperacao, String cpfCnpj, String conta,
                          String limiteOperacoes, String expand, String fields) {

    static final String HEADER_TIPO_CONSULTA = "x-tipo-consulta";
    static final String ID_OPERACAO = "id_operacao";
    static final String CPF_CNPJ = "cpf_cnpj";
    static final String CONTA = "conta";
    static final String LIMITE_OPERACOES = "limite_operacoes";
    static final String EXPAND = "expand";
    static final String FIELDS = "fields";
    static final String FILTER = "filter";

    /** Parâmetro que identifica o que se consulta em cada tipo de consulta. */
    private static final Map<TipoConsulta, String> IDENTIFICADOR = new EnumMap<>(Map.of(
            TipoConsulta.OPERACAO, ID_OPERACAO,
            TipoConsulta.CLIENTE, CPF_CNPJ,
            TipoConsulta.CONTA, CONTA));

    /** Parâmetros do contrato: cada um pode vir no máximo uma vez (listas vão separadas por vírgula num só). */
    static final List<String> PARAMETROS = List.of(ID_OPERACAO, CPF_CNPJ, CONTA, LIMITE_OPERACOES, EXPAND, FIELDS,
            FILTER);
    /** {@code id_operacao} bem formado: positivo, até 18 dígitos (cabe em {@code long}). */
    static final Pattern NUMERO_OPERACAO = Pattern.compile("[1-9]\\d{0,17}");

    private static final Set<TipoConsulta> ACEITAM_LIMITE = Set.of(TipoConsulta.CLIENTE, TipoConsulta.CONTA);
    private static final Pattern CONTA_VALIDA =
            Pattern.compile("\\d{1," + CriterioConsulta.PorConta.TAMANHO_MAXIMO_CONTA + "}");
    private static final Pattern LIMITE = Pattern.compile("\\d{1,4}");

    /**
     * Rejeita parâmetro do contrato repetido ({@code filter=a&filter=b}): o Spring juntaria os valores com vírgula,
     * o que mudaria o sentido do pedido (dois filtros viram um "ou") sem o consumidor saber.
     *
     * @param parametros parâmetros da requisição, como em {@code HttpServletRequest#getParameterMap()}
     */
    static void rejeitarRepetidos(Map<String, String[]> parametros) {
        PARAMETROS.stream()
                .filter(parametro -> parametros.get(parametro) != null && parametros.get(parametro).length > 1)
                .findFirst()
                .ifPresent(parametro -> {
                    throw new RequisicaoInvalidaException("Parâmetro " + parametro + " informado mais de uma vez; "
                            + "informe-o uma única vez (listas vão separadas por vírgula no mesmo parâmetro)");
                });
    }

    CriterioConsulta criterio() {
        var tipo = tipo();
        validarCoerencia(tipo);
        return switch (tipo) {
            case OPERACAO -> new CriterioConsulta.PorOperacao(idOperacaoNumerico());
            case CLIENTE -> new CriterioConsulta.PorCliente(cpfCnpjValido(), limite());
            case CONTA -> new CriterioConsulta.PorConta(contaValida(), limite());
        };
    }

    private String cpfCnpjValido() {
        if (!CpfCnpj.valido(cpfCnpj)) {
            throw new RequisicaoInvalidaException("Parâmetro " + CPF_CNPJ + " deve ser um CPF (11 dígitos) ou CNPJ "
                    + "(14 caracteres, numérico ou alfanumérico em maiúsculas) com dígito verificador válido");
        }
        return cpfCnpj;
    }

    private String contaValida() {
        if (!CONTA_VALIDA.matcher(conta).matches()) {
            throw new RequisicaoInvalidaException("Parâmetro " + CONTA + " deve ter só dígitos, de 1 a "
                    + CriterioConsulta.PorConta.TAMANHO_MAXIMO_CONTA + " dígitos");
        }
        return conta;
    }

    /** {@code limite_operacoes} de 1 a 500; ausente devolve todas as operações. */
    private OptionalInt limite() {
        if (limiteOperacoes == null) {
            return OptionalInt.empty();
        }
        var invalido = new RequisicaoInvalidaException("Parâmetro " + LIMITE_OPERACOES
                + " deve ser um inteiro de 1 a " + CriterioConsulta.LIMITE_OPERACOES_MAXIMO);
        if (!LIMITE.matcher(limiteOperacoes).matches()) {
            throw invalido;
        }
        int limite = Integer.parseInt(limiteOperacoes);
        if (limite < 1 || limite > CriterioConsulta.LIMITE_OPERACOES_MAXIMO) {
            throw invalido;
        }
        return OptionalInt.of(limite);
    }

    /**
     * Blocos pedidos no {@code expand} (lista separada por vírgula, nomes canônicos).
     * Ausente ou vazio: nenhum bloco além dos que vêm sempre.
     */
    Set<Bloco> blocos() {
        if (expand == null || expand.isBlank()) {
            return Set.of();
        }
        var blocos = EnumSet.noneOf(Bloco.class);
        for (var nome : expand.split(",", -1)) {
            var nomeLimpo = nome.strip();
            blocos.add(Bloco.porNome(nomeLimpo).orElseThrow(() -> blocoDesconhecido(nomeLimpo)));
        }
        return blocos;
    }

    /**
     * Itens do {@code fields} (lista separada por vírgula, {@code bloco.campo}), sem espaços nas pontas.
     * Ausente ou vazio: nenhum. A validação contra o catálogo é do caso de uso.
     */
    List<String> campos() {
        if (fields == null || fields.isBlank()) {
            return List.of();
        }
        return Arrays.stream(fields.split(",", -1)).map(String::strip).toList();
    }

    private static RequisicaoInvalidaException blocoDesconhecido(String nome) {
        var validos = Arrays.stream(Bloco.values()).map(Bloco::nome).collect(Collectors.joining(", "));
        return new RequisicaoInvalidaException(
                "Bloco '" + nome + "' inválido no parâmetro " + EXPAND + ". Blocos válidos: " + validos);
    }

    private TipoConsulta tipo() {
        var validos = Arrays.stream(TipoConsulta.values()).map(Enum::name).collect(Collectors.joining(", "));
        if (tipoConsulta == null || tipoConsulta.isBlank()) {
            throw new RequisicaoInvalidaException(
                    "Header " + HEADER_TIPO_CONSULTA + " é obrigatório. Valores válidos: " + validos);
        }
        return Arrays.stream(TipoConsulta.values())
                .filter(tipo -> tipo.name().equals(tipoConsulta))
                .findFirst()
                .orElseThrow(() -> new RequisicaoInvalidaException("Header " + HEADER_TIPO_CONSULTA
                        + " inválido. Valores válidos: " + validos));
    }

    private void validarCoerencia(TipoConsulta tipo) {
        var identificadores = identificadoresInformados();
        var esperado = IDENTIFICADOR.get(tipo);
        identificadores.keySet().stream()
                .filter(parametro -> !parametro.equals(esperado))
                .findFirst()
                .ifPresent(parametro -> {
                    throw naoSeAplica(parametro, tipo);
                });
        if (limiteOperacoes != null && !ACEITAM_LIMITE.contains(tipo)) {
            throw naoSeAplica(LIMITE_OPERACOES, tipo);
        }
        if (!identificadores.containsKey(esperado)) {
            throw new RequisicaoInvalidaException(
                    "Parâmetro " + esperado + " é obrigatório para " + HEADER_TIPO_CONSULTA + ": " + tipo);
        }
    }

    private Map<String, String> identificadoresInformados() {
        var informados = new LinkedHashMap<String, String>();
        putSeInformado(informados, ID_OPERACAO, idOperacao);
        putSeInformado(informados, CPF_CNPJ, cpfCnpj);
        putSeInformado(informados, CONTA, conta);
        return informados;
    }

    private static void putSeInformado(Map<String, String> informados, String parametro, String valor) {
        if (valor != null && !valor.isBlank()) {
            informados.put(parametro, valor);
        }
    }

    private long idOperacaoNumerico() {
        if (!NUMERO_OPERACAO.matcher(idOperacao).matches()) {
            throw new RequisicaoInvalidaException(
                    "Parâmetro " + ID_OPERACAO + " deve ser numérico, positivo e ter até 18 dígitos");
        }
        return Long.parseLong(idOperacao);
    }

    private static RequisicaoInvalidaException naoSeAplica(String parametro, TipoConsulta tipo) {
        return new RequisicaoInvalidaException(
                "Parâmetro " + parametro + " não se aplica a " + HEADER_TIPO_CONSULTA + ": " + tipo);
    }
}
