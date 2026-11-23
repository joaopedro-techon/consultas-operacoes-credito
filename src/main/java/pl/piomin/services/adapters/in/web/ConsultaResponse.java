package pl.piomin.services.adapters.in.web;

import pl.piomin.services.domain.OperacaoConsultada;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Corpo de resposta: sempre {@code {"data": [...]}}, uma chave por bloco em cada operação. */
public record ConsultaResponse(List<Map<String, Object>> data) {

    static ConsultaResponse de(List<OperacaoConsultada> operacoes) {
        return new ConsultaResponse(operacoes.stream().map(ConsultaResponse::paraJson).toList());
    }

    private static Map<String, Object> paraJson(OperacaoConsultada operacao) {
        var json = new LinkedHashMap<String, Object>();
        operacao.blocos().forEach((bloco, conteudo) -> json.put(bloco.nome(), conteudo));
        return json;
    }
}
