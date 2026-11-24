package br.com.consultas.operacoescredito.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** Segmento da rota da fonte primária; sem ele, a consulta vai à visão consolidada. */
    static final String FONTE_PRIMARIA = "fonte-primaria";

    private final ConsultarOperacoesUseCase visaoConsolidada;
    private final ConsultarOperacoesUseCase fontePrimaria;

    OperacoesCreditoController(ConsultarOperacoesUseCase visaoConsolidada,
                               @Qualifier("fontePrimaria") ConsultarOperacoesUseCase fontePrimaria) {
        this.visaoConsolidada = visaoConsolidada;
        this.fontePrimaria = fontePrimaria;
    }

    /**
     * Consulta na visão consolidada ({@code /v1/operacoes-credito}) ou na fonte primária
     * ({@code /v1/operacoes-credito/fonte-primaria}): mesmo contrato, mesmo caso de uso, outra fonte.
     * Parâmetro do contrato repetido é 400 (o Spring juntaria os valores com vírgula).
     */
    @GetMapping({"", "/{rota:" + FONTE_PRIMARIA + "}"})
    ResponseEntity<ConsultaResponse> consultar(
            @PathVariable(name = "rota", required = false) String rota,
            @RequestHeader(name = ParametrosConsulta.HEADER_TIPO_CONSULTA, required = false) String tipoConsulta,
            @RequestParam(name = ParametrosConsulta.ID_OPERACAO, required = false) String idOperacao,
            @RequestParam(name = ParametrosConsulta.CPF_CNPJ, required = false) String cpfCnpj,
            @RequestParam(name = ParametrosConsulta.CONTA, required = false) String conta,
            @RequestParam(name = ParametrosConsulta.LIMITE_OPERACOES, required = false) String limiteOperacoes,
            @RequestParam(name = ParametrosConsulta.EXPAND, required = false) String expand,
            @RequestParam(name = ParametrosConsulta.FIELDS, required = false) String fields,
            @RequestParam(name = ParametrosConsulta.FILTER, required = false) String filter,
            HttpServletRequest requisicao) {
        ParametrosConsulta.rejeitarRepetidos(requisicao.getParameterMap());
        var parametros = new ParametrosConsulta(tipoConsulta, idOperacao, cpfCnpj, conta, limiteOperacoes, expand, fields);
        var consultarOperacoes = rota == null ? visaoConsolidada : fontePrimaria;
        var operacoes = consultarOperacoes.consultar(new Consulta(
                parametros.criterio(), parametros.blocos(), parametros.campos(), FiltroRsql.traduzir(filter)));
        requisicao.setAttribute(RegistroDeConsumoHttp.ATRIBUTO_QUANTIDADE_OPERACOES, operacoes.size());
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
