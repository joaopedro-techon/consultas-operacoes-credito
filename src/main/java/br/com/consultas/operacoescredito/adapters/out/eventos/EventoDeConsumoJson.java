package br.com.consultas.operacoescredito.adapters.out.eventos;

import br.com.consultas.operacoescredito.application.port.out.EventoDeConsumo;
import tools.jackson.databind.json.JsonMapper;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Corpo da mensagem SQS: JSON plano em snake_case, na ordem do contrato ({@code docs/eventos/evento-consumo.md}).
 * Campos opcionais sem valor ficam fora; listas e contadores de cache sempre presentes (vazios se for o caso).
 */
final class EventoDeConsumoJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EventoDeConsumoJson() {
    }

    static String serializar(EventoDeConsumo evento) {
        return JSON.writeValueAsString(campos(evento));
    }

    static Map<String, Object> campos(EventoDeConsumo evento) {
        var campos = new LinkedHashMap<String, Object>();
        campos.put("versao_evento", evento.versaoEvento());
        campos.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(evento.timestamp()));
        campos.put("correlation_id", evento.correlationId());
        seInformado(campos, "trace_id", evento.traceId());
        campos.put("consumidor", evento.consumidor());
        campos.put("rota", evento.rota());
        seInformado(campos, "tipo_consulta", evento.tipoConsulta());
        seInformado(campos, "id_operacao", evento.idOperacao());
        seInformado(campos, "cpf_cnpj_hmac", evento.cpfCnpjHmac());
        seInformado(campos, "conta_hmac", evento.contaHmac());
        campos.put("blocos_solicitados", evento.blocosSolicitados());
        campos.put("campos_solicitados", evento.camposSolicitados());
        seInformado(campos, "filtro", evento.filtro());
        seInformado(campos, "limite_operacoes", evento.limiteOperacoes());
        campos.put("quantidade_operacoes_retornadas", evento.quantidadeOperacoesRetornadas());
        campos.put("blocos_indisponiveis", evento.blocosIndisponiveis());
        campos.put("cache_hits", new TreeMap<>(evento.cacheHits()));
        campos.put("cache_misses", new TreeMap<>(evento.cacheMisses()));
        campos.put("status_http", evento.statusHttp());
        seInformado(campos, "codigo_erro", evento.codigoErro());
        campos.put("duracao_ms", evento.duracaoMs());
        return campos;
    }

    private static void seInformado(Map<String, Object> campos, String nome, Object valor) {
        if (valor != null) {
            campos.put(nome, valor);
        }
    }
}
