package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.TipoConsultaNaoSuportadoException;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fonte primária (mainframe via gateway, ADR-0002: sem cache). Uma chamada por bloco pedido, em paralelo
 * em virtual threads com o contexto da requisição (MDC, trace); cada trancode é convertido pelo
 * {@link ParserTrancode} com o layout do bloco no mesmo conteúdo que a visão consolidada produz.
 * Bloco inexistente no gateway (404) vem {@code null}; sem o bloco {@code operacao}, a operação não existe.
 */
final class FontePrimariaTrancodeAdapter implements FonteDeOperacoes {

    private static final String FONTE = "fonte primária";
    /** Valor da tag {@code dependencia} nos spans e timers das chamadas ao gateway. */
    static final String DEPENDENCIA = "fonte_primaria";
    static final String ETAPA_FAN_OUT = "fan_out";

    private final GatewayMainframeClient gateway;
    private final LayoutsTrancode layouts;
    private final ObservationRegistry observationRegistry;
    private final ParserTrancode parser = new ParserTrancode();
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    FontePrimariaTrancodeAdapter(GatewayMainframeClient gateway, LayoutsTrancode layouts,
                                 ObservationRegistry observationRegistry) {
        this.gateway = gateway;
        this.layouts = layouts;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
        return switch (criterio) {
            case CriterioConsulta.PorOperacao porOperacao -> buscarPorOperacao(porOperacao.idOperacao(), blocos);
            // CLIENTE e CONTA na fonte primária chegam com o fan-out (ticket #13)
            default -> throw new TipoConsultaNaoSuportadoException(criterio.tipo(), FONTE);
        };
    }

    private List<OperacaoConsultada> buscarPorOperacao(long idOperacao, Set<Bloco> blocos) {
        var conteudos = Observacoes.etapa(observationRegistry, ETAPA_FAN_OUT)
                .observe(() -> lerBlocosEmParalelo(idOperacao, blocos));
        if (conteudos.get(Bloco.OPERACAO).isEmpty()) {
            return List.of();
        }
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        conteudos.forEach((bloco, valor) -> conteudo.put(bloco, valor.orElse(null)));
        return List.of(new OperacaoConsultada(conteudo));
    }

    private Map<Bloco, Optional<Object>> lerBlocosEmParalelo(long idOperacao, Set<Bloco> blocos) {
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var tarefas = new EnumMap<Bloco, Future<Optional<Object>>>(Bloco.class);
            blocos.forEach(bloco -> tarefas.put(bloco, executor.submit(() -> lerBloco(idOperacao, bloco))));
            var conteudos = new EnumMap<Bloco, Optional<Object>>(Bloco.class);
            for (var tarefa : tarefas.entrySet()) {
                conteudos.put(tarefa.getKey(), resultado(tarefa.getValue()));
            }
            return conteudos;
        }
    }

    private Optional<Object> lerBloco(long idOperacao, Bloco bloco) {
        return trancode(idOperacao, bloco).map(trancode -> parser.ler(trancode, layouts.de(bloco)));
    }

    /** Uma chamada ao gateway, com span e timer de dependência; 404 (bloco inexistente) não é erro. */
    private Optional<String> trancode(long idOperacao, Bloco bloco) {
        var observacao = Observacoes.dependencia(observationRegistry, DEPENDENCIA, bloco.nome()).start();
        try (var _ = observacao.openScope()) {
            return Optional.of(Objects.requireNonNullElse(gateway.bloco(idOperacao, bloco.nome()), ""));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            observacao.error(e);
            throw new FalhaNaFonteDeOperacoesException(
                    "Falha ao consultar o bloco " + bloco.nome() + " na " + FONTE, e);
        } finally {
            observacao.stop();
        }
    }

    private static Optional<Object> resultado(Future<Optional<Object>> tarefa) {
        try {
            return tarefa.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            throw new FalhaNaFonteDeOperacoesException("Falha ao consultar a " + FONTE, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FalhaNaFonteDeOperacoesException("Consulta à " + FONTE + " interrompida", e);
        }
    }
}
