package br.com.consultas.operacoescredito.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import br.com.consultas.operacoescredito.application.port.in.Consulta;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;

@RestController
@RequestMapping("/v1/operacoes-credito")
class OperacoesCreditoController {

    private final ConsultarOperacoesUseCase consultarOperacoes;

    OperacoesCreditoController(ConsultarOperacoesUseCase consultarOperacoes) {
        this.consultarOperacoes = consultarOperacoes;
    }

    /** Consulta na visão consolidada. */
    @GetMapping
    ResponseEntity<ConsultaResponse> consultar(
            @RequestHeader(name = ParametrosConsulta.HEADER_TIPO_CONSULTA, required = false) String tipoConsulta,
            @RequestParam(name = ParametrosConsulta.ID_OPERACAO, required = false) String idOperacao,
            @RequestParam(name = ParametrosConsulta.CPF_CNPJ, required = false) String cpfCnpj,
            @RequestParam(name = ParametrosConsulta.CONTA, required = false) String conta,
            @RequestParam(name = ParametrosConsulta.LIMITE_OPERACOES, required = false) String limiteOperacoes) {
        var parametros = new ParametrosConsulta(tipoConsulta, idOperacao, cpfCnpj, conta, limiteOperacoes);
        var operacoes = consultarOperacoes.consultar(Consulta.comBlocosPadrao(parametros.criterio()));
        return ResponseEntity.ok(ConsultaResponse.de(operacoes));
    }
}
