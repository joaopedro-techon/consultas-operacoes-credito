package pl.piomin.services.adapters.in.web;

import pl.piomin.services.domain.CriterioConsulta;
import pl.piomin.services.domain.TipoConsulta;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Header e query params da consulta. Valida a coerência entre o tipo de consulta e os parâmetros
 * e converte o pedido em {@link CriterioConsulta}.
 */
record ParametrosConsulta(String tipoConsulta, String idOperacao, String cpfCnpj, String conta,
                          String limiteOperacoes) {

    static final String HEADER_TIPO_CONSULTA = "x-tipo-consulta";
    static final String ID_OPERACAO = "id_operacao";
    static final String CPF_CNPJ = "cpf_cnpj";
    static final String CONTA = "conta";
    static final String LIMITE_OPERACOES = "limite_operacoes";

    /** Parâmetro que identifica o que se consulta em cada tipo de consulta. */
    private static final Map<TipoConsulta, String> IDENTIFICADOR = new EnumMap<>(Map.of(
            TipoConsulta.OPERACAO, ID_OPERACAO,
            TipoConsulta.CLIENTE, CPF_CNPJ,
            TipoConsulta.CONTA, CONTA));

    private static final Set<TipoConsulta> ACEITAM_LIMITE = Set.of(TipoConsulta.CLIENTE, TipoConsulta.CONTA);
    private static final Pattern NUMERO_OPERACAO = Pattern.compile("[1-9]\\d{0,17}");

    CriterioConsulta criterio() {
        var tipo = tipo();
        validarCoerencia(tipo);
        return switch (tipo) {
            case OPERACAO -> new CriterioConsulta.PorOperacao(idOperacaoNumerico());
            case CLIENTE, CONTA -> throw new TipoConsultaNaoImplementadoException(tipo);
        };
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
                .orElseThrow(() -> new RequisicaoInvalidaException("Valor '" + tipoConsulta + "' inválido para o header "
                        + HEADER_TIPO_CONSULTA + ". Valores válidos: " + validos));
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
