package br.com.consultas.operacoescredito.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Evento de consumo, já sem dado pessoal em claro: CPF/CNPJ e conta só como HMAC-SHA256 (hex minúsculo), também
 * dentro do filtro. Contrato em {@code docs/eventos/evento-consumo.md}; mudança incompatível incrementa
 * {@link #VERSAO}. Os campos seguem os de {@link br.com.consultas.operacoescredito.application.port.in.Consumo}.
 */
public record EventoDeConsumo(int versaoEvento, Instant timestamp, String correlationId, String traceId,
                              String consumidor, String rota, String tipoConsulta, Long idOperacao,
                              String cpfCnpjHmac, String contaHmac, List<String> blocosSolicitados,
                              List<String> camposSolicitados, String filtro, Integer limiteOperacoes,
                              int quantidadeOperacoesRetornadas, List<String> blocosIndisponiveis,
                              Map<String, Long> cacheHits, Map<String, Long> cacheMisses, int statusHttp,
                              String codigoErro, long duracaoMs) {

    /** Versão atual do schema do evento. */
    public static final int VERSAO = 1;
}
