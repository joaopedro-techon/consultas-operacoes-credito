package br.com.consultas.operacoescredito.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import br.com.consultas.operacoescredito.application.port.in.Consulta;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/operacoes-credito")
class OperacoesCreditoController {

    static final String HEADER_BLOCOS_INDISPONIVEIS = "x-blocos-indisponiveis";

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
            @RequestParam(name = ParametrosConsulta.LIMITE_OPERACOES, required = false) String limiteOperacoes,
            @RequestParam(name = ParametrosConsulta.EXPAND, required = false) String expand,
            @RequestParam(name = ParametrosConsulta.FIELDS, required = false) String fields) {
        var parametros = new ParametrosConsulta(tipoConsulta, idOperacao, cpfCnpj, conta, limiteOperacoes, expand, fields);
        var operacoes = consultarOperacoes.consultar(
                new Consulta(parametros.criterio(), parametros.blocos(), parametros.campos()));
        var resposta = ResponseEntity.ok();
        blocosIndisponiveis(operacoes).ifPresent(blocos -> resposta.header(HEADER_BLOCOS_INDISPONIVEIS, blocos));
        return resposta.body(ConsultaResponse.de(operacoes));
    }

    /** Blocos pedidos e ausentes em alguma operação, separados por vírgula na ordem canônica. */
    private static Optional<String> blocosIndisponiveis(List<OperacaoConsultada> operacoes) {
        var indisponiveis = EnumSet.noneOf(Bloco.class);
        operacoes.forEach(operacao -> indisponiveis.addAll(operacao.blocosIndisponiveis()));
        return indisponiveis.isEmpty()
                ? Optional.empty()
                : Optional.of(indisponiveis.stream().map(Bloco::nome).collect(Collectors.joining(",")));
    }
}
