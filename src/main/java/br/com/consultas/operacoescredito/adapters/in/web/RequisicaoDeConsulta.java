package br.com.consultas.operacoescredito.adapters.in.web;

import br.com.consultas.operacoescredito.domain.TipoConsulta;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Leitura única, para toda a borda HTTP, do contexto de uma requisição: se é uma rota de consulta, rota, tipo de
 * consulta, consumidor e blocos pedidos. Filtro de contexto (MDC e log de acesso), evento de consumo, prazo e
 * métricas usam só estas regras, para que nunca divirjam.
 */
final class RequisicaoDeConsulta {

    static final String HEADER_CONSUMIDOR = "x-consumidor";
    static final String CONSUMIDOR_DESCONHECIDO = "desconhecido";
    static final String INVALIDO = "invalido";
    static final String ROTA_VISAO_CONSOLIDADA = "visao_consolidada";
    static final String ROTA_FONTE_PRIMARIA = "fonte_primaria";

    private static final String CAMINHO_CONSULTA = "/v1/operacoes-credito";
    private static final String CAMINHO_FONTE_PRIMARIA = CAMINHO_CONSULTA + "/" + OperacoesCreditoController.FONTE_PRIMARIA;
    /**
     * Formato aceito do {@code x-consumidor} (id da API key). Fora dele o valor vira {@value #INVALIDO} em logs,
     * evento e métricas: o header não entra cru em lugar nenhum (cardinalidade e injeção em logs).
     */
    private static final Pattern CONSUMIDOR_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final List<String> BLOCOS_SEMPRE = List.of("operacao", "originacao");

    private RequisicaoDeConsulta() {
    }

    /** {@code true} só para as duas rotas exatas de consulta (visão consolidada e fonte primária). */
    static boolean ehRotaDeConsulta(HttpServletRequest request) {
        var caminho = caminho(request);
        return caminho.equals(CAMINHO_CONSULTA) || caminho.equals(CAMINHO_FONTE_PRIMARIA);
    }

    /** {@value #ROTA_FONTE_PRIMARIA} na rota exata da fonte primária; {@value #ROTA_VISAO_CONSOLIDADA} nas demais. */
    static String rota(HttpServletRequest request) {
        return caminho(request).equals(CAMINHO_FONTE_PRIMARIA) ? ROTA_FONTE_PRIMARIA : ROTA_VISAO_CONSOLIDADA;
    }

    /** {@code x-consumidor} sem espaços nas pontas; ausente: {@value #CONSUMIDOR_DESCONHECIDO}; fora do formato: {@value #INVALIDO}. */
    static String consumidor(HttpServletRequest request) {
        var consumidor = request.getHeader(HEADER_CONSUMIDOR);
        if (consumidor == null || consumidor.isBlank()) {
            return CONSUMIDOR_DESCONHECIDO;
        }
        var limpo = consumidor.strip();
        return CONSUMIDOR_VALIDO.matcher(limpo).matches() ? limpo : INVALIDO;
    }

    /** Nome do tipo de consulta, {@value #INVALIDO} se fora dos valores válidos, {@code null} se ausente. */
    static String tipoConsulta(HttpServletRequest request) {
        var tipo = request.getHeader(ParametrosConsulta.HEADER_TIPO_CONSULTA);
        if (tipo == null || tipo.isBlank()) {
            return null;
        }
        return tipoValido(tipo).map(Enum::name).orElse(INVALIDO);
    }

    /** O tipo de consulta do header, se for um dos válidos. */
    static Optional<TipoConsulta> tipoConsultaValido(HttpServletRequest request) {
        var tipo = request.getHeader(ParametrosConsulta.HEADER_TIPO_CONSULTA);
        return tipo == null ? Optional.empty() : tipoValido(tipo);
    }

    /** Blocos pedidos: {@code operacao} e {@code originacao} sempre, mais os do {@code expand}, sem repetição. */
    static List<String> blocos(HttpServletRequest request) {
        var blocos = new LinkedHashSet<>(BLOCOS_SEMPRE);
        var expand = request.getParameter(ParametrosConsulta.EXPAND);
        if (expand != null) {
            Arrays.stream(expand.split(","))
                    .map(String::strip)
                    .filter(bloco -> !bloco.isEmpty())
                    .forEach(blocos::add);
        }
        return List.copyOf(blocos);
    }

    private static Optional<TipoConsulta> tipoValido(String tipo) {
        return Arrays.stream(TipoConsulta.values()).filter(valido -> valido.name().equals(tipo)).findFirst();
    }

    private static String caminho(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
