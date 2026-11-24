package br.com.consultas.operacoescredito.application.service;

import br.com.consultas.operacoescredito.application.port.in.Consumo;
import br.com.consultas.operacoescredito.application.port.in.RegistrarConsumoUseCase;
import br.com.consultas.operacoescredito.application.port.out.EventoDeConsumo;
import br.com.consultas.operacoescredito.application.port.out.PublicadorDeEventosDeConsumo;

import java.util.Objects;

/**
 * Transforma o consumo de uma requisição no evento de consumo (CPF/CNPJ e conta só como HMAC-SHA256 com o segredo
 * da aplicação, também dentro do filtro) e o entrega ao publicador, que só enfileira (ADR-0004).
 */
public final class RegistrarConsumoService implements RegistrarConsumoUseCase {

    private final PublicadorDeEventosDeConsumo publicador;
    private final PseudonimizadorHmac pseudonimizador;

    public RegistrarConsumoService(PublicadorDeEventosDeConsumo publicador, byte[] segredoHmac) {
        this.publicador = Objects.requireNonNull(publicador, "publicador");
        this.pseudonimizador = new PseudonimizadorHmac(segredoHmac);
    }

    @Override
    public void registrar(Consumo consumo) {
        publicador.publicar(new EventoDeConsumo(
                EventoDeConsumo.VERSAO,
                consumo.timestamp(),
                consumo.correlationId(),
                consumo.traceId(),
                consumo.consumidor(),
                consumo.rota(),
                consumo.tipoConsulta(),
                consumo.idOperacao(),
                pseudonimizador.hmac(consumo.cpfCnpj()),
                pseudonimizador.hmac(consumo.conta()),
                consumo.blocosSolicitados(),
                consumo.camposSolicitados(),
                pseudonimizador.filtro(consumo.filtro()),
                consumo.limiteOperacoes(),
                consumo.quantidadeOperacoesRetornadas(),
                consumo.blocosIndisponiveis(),
                consumo.cacheHits(),
                consumo.cacheMisses(),
                consumo.statusHttp(),
                consumo.codigoErro(),
                consumo.duracaoMs()));
    }
}
