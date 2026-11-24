package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fonte primária (mainframe via gateway, ADR-0002: sem cache). Cada trancode é convertido pelo
 * {@link ParserTrancode} com o layout do bloco no mesmo conteúdo que a visão consolidada produz.
 * <ul>
 *     <li>OPERACAO: uma chamada por bloco pedido;</li>
 *     <li>CLIENTE/CONTA: a lista de operações do cliente ou da conta (etapa {@value #ETAPA_LISTA}), mais
 *     recentes primeiro, cortada em {@code limite_operacoes}; depois uma chamada por operação × bloco.</li>
 * </ul>
 * As chamadas de bloco rodam em paralelo (etapa {@value #ETAPA_FAN_OUT}), em virtual threads com o contexto
 * da requisição (MDC, trace) e no máximo {@code maxChamadasSimultaneas} ao mesmo tempo por requisição.
 * A primeira chamada que falhar derruba a requisição inteira (sem resposta parcial) e interrompe as demais.
 * Cada chamada passa pela {@link ResilienciaFontePrimaria}, e nada espera além do {@link PrazoDaRequisicao}:
 * nem a vaga no fan-out, nem a resposta do gateway, nem o fan-out como um todo (504).
 * Bloco inexistente no gateway (404) vem {@code null}; sem o bloco {@code operacao}, a operação não existe.
 */
final class FontePrimariaTrancodeAdapter implements FonteDeOperacoes {

    private static final String FONTE = "fonte primária";
    /** Valor da tag {@code dependencia} nos spans e timers das chamadas ao gateway. */
    static final String DEPENDENCIA = "fonte_primaria";
    static final String ETAPA_FAN_OUT = "fan_out";
    static final String ETAPA_LISTA = "lista_operacoes";
    /** Valores da tag {@code operacao} nas chamadas às listas de operações. */
    static final String LISTA_DO_CLIENTE = "lista_operacoes_cliente";
    static final String LISTA_DA_CONTA = "lista_operacoes_conta";

    /** Mais recentes primeiro por data de contratação; no empate, maior {@code id_operacao} primeiro. */
    private static final Comparator<OperacaoListada> MAIS_RECENTE_PRIMEIRO =
            Comparator.comparing(OperacaoListada::dataContratacao)
                    .thenComparingLong(OperacaoListada::idOperacao)
                    .reversed();

    private final GatewayMainframeClient gateway;
    private final LayoutsTrancode layouts;
    private final ObservationRegistry observationRegistry;
    private final ResilienciaFontePrimaria resiliencia;
    private final int maxChamadasSimultaneas;
    private final ParserTrancode parser = new ParserTrancode();
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    FontePrimariaTrancodeAdapter(GatewayMainframeClient gateway, LayoutsTrancode layouts,
                                 ObservationRegistry observationRegistry, ResilienciaFontePrimaria resiliencia,
                                 int maxChamadasSimultaneas) {
        if (maxChamadasSimultaneas < 1) {
            throw new IllegalArgumentException("maxChamadasSimultaneas deve ser ao menos 1");
        }
        this.gateway = gateway;
        this.layouts = layouts;
        this.observationRegistry = observationRegistry;
        this.resiliencia = Objects.requireNonNull(resiliencia, "resiliencia");
        this.maxChamadasSimultaneas = maxChamadasSimultaneas;
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
        return switch (criterio) {
            case CriterioConsulta.PorOperacao porOperacao -> detalhar(List.of(porOperacao.idOperacao()), blocos);
            case CriterioConsulta.PorCliente porCliente -> buscarPorLista(LISTA_DO_CLIENTE,
                    () -> gateway.operacoesDoCliente(porCliente.cpfCnpj()), porCliente.limiteOperacoes(), blocos);
            case CriterioConsulta.PorConta porConta -> buscarPorLista(LISTA_DA_CONTA,
                    () -> gateway.operacoesDaConta(porConta.conta()), porConta.limiteOperacoes(), blocos);
        };
    }

    private List<OperacaoConsultada> buscarPorLista(String lista, Supplier<String> chamada, OptionalInt limite,
                                                    Set<Bloco> blocos) {
        var ids = Observacoes.etapa(observationRegistry, ETAPA_LISTA)
                .observe(() -> listarOperacoes(lista, chamada, limite));
        return ids.isEmpty() ? List.of() : detalhar(ids, blocos);
    }

    /**
     * Lê a lista de operações e aplica o limite antes do fan-out: só as operações selecionadas são detalhadas.
     * A lista é reordenada (mais recentes primeiro) para que o corte não dependa da ordem do gateway.
     * 404 do gateway: cliente ou conta sem operações.
     */
    private List<Long> listarOperacoes(String lista, Supplier<String> chamada, OptionalInt limite) {
        var trancode = chamar(lista, "a lista de operações", chamada);
        if (trancode.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        var itens = (List<Map<String, Object>>) parser.ler(trancode.get(), layouts.listaOperacoes());
        var operacoes = new ArrayList<OperacaoListada>(itens.size());
        for (var item : itens) {
            operacoes.add(OperacaoListada.de(item));
        }
        operacoes.sort(MAIS_RECENTE_PRIMEIRO);
        var selecionadas = operacoes.stream().map(OperacaoListada::idOperacao).distinct();
        return (limite.isPresent() ? selecionadas.limit(limite.getAsInt()) : selecionadas).toList();
    }

    /** Fan-out de uma chamada por operação × bloco; devolve as operações na ordem de {@code ids}. */
    private List<OperacaoConsultada> detalhar(List<Long> ids, Set<Bloco> blocos) {
        var conteudos = Observacoes.etapa(observationRegistry, ETAPA_FAN_OUT)
                .highCardinalityKeyValue("operacoes", Integer.toString(ids.size()))
                .observe(() -> lerBlocosEmParalelo(ids, blocos));
        var operacoes = new ArrayList<OperacaoConsultada>(ids.size());
        for (var id : ids) {
            var blocosDaOperacao = conteudos.get(id);
            // Sem o bloco operacao a operação não existe (ou deixou de existir depois da lista)
            if (blocosDaOperacao.get(Bloco.OPERACAO).isEmpty()) {
                continue;
            }
            var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
            blocosDaOperacao.forEach((bloco, valor) -> conteudo.put(bloco, valor.orElse(null)));
            operacoes.add(new OperacaoConsultada(conteudo));
        }
        return operacoes;
    }

    private Map<Long, Map<Bloco, Optional<Object>>> lerBlocosEmParalelo(List<Long> ids, Set<Bloco> blocos) {
        var vagas = new Semaphore(maxChamadasSimultaneas);
        var conteudos = new HashMap<Long, Map<Bloco, Optional<Object>>>();
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto)) {
            var concluidas = new ExecutorCompletionService<BlocoLido>(executor);
            var chamadas = 0;
            for (var id : ids) {
                conteudos.put(id, new EnumMap<>(Bloco.class));
                for (var bloco : blocos) {
                    concluidas.submit(() -> lerBlocoNaVez(vagas, id, bloco));
                    chamadas++;
                }
            }
            try {
                for (int i = 0; i < chamadas; i++) {
                    var lido = resultado(concluidas);
                    conteudos.get(lido.idOperacao()).put(lido.bloco(), lido.conteudo());
                }
            } catch (RuntimeException e) {
                // Sem resposta parcial: a primeira falha interrompe as chamadas em andamento e as que esperam vaga
                executor.shutdownNow();
                throw e;
            }
        }
        return conteudos;
    }

    private BlocoLido lerBlocoNaVez(Semaphore vagas, long idOperacao, Bloco bloco) throws InterruptedException {
        var restante = PrazoDaRequisicao.restante();
        if (restante.isEmpty()) {
            vagas.acquire();
        } else if (!vagas.tryAcquire(restante.get().toNanos(), TimeUnit.NANOSECONDS)) {
            throw prazoDaRequisicaoEsgotado("o bloco " + bloco.nome());
        }
        try {
            var conteudo = chamar(bloco.nome(), "o bloco " + bloco.nome(),
                    () -> gateway.bloco(idOperacao, bloco.nome()))
                    .map(trancode -> parser.ler(trancode, layouts.de(bloco)));
            return new BlocoLido(idOperacao, bloco, conteudo);
        } finally {
            vagas.release();
        }
    }

    /**
     * Uma chamada ao gateway protegida por {@link ResilienciaFontePrimaria} (retry, circuit breaker, rate limiter,
     * bulkhead); 404 não é erro e vem vazio. Prazo esgotado (conexão, pool, resposta ou prazo da requisição) é
     * distinguido das demais falhas.
     */
    private Optional<String> chamar(String operacao, String descricao, Supplier<String> chamada) {
        try {
            return resiliencia.executar(() -> tentar(operacao, descricao, chamada));
        } catch (RestClientException e) {
            var mensagem = "Falha ao consultar " + descricao + " na " + FONTE;
            if (prazoEsgotado(e)) {
                throw new TempoEsgotadoNaFonteDeOperacoesException(mensagem + ": prazo esgotado", e);
            }
            throw new FalhaNaFonteDeOperacoesException(mensagem, e);
        }
    }

    /**
     * Uma tentativa, com span e timer de dependência próprios ({@code operacao} = bloco ou lista). Não sai se o
     * prazo da requisição já acabou; o timeout de cada tentativa é limitado ao que resta dele (na fábrica de
     * requisições do {@code RestClient}).
     */
    private Optional<String> tentar(String operacao, String descricao, Supplier<String> chamada) {
        if (PrazoDaRequisicao.esgotado()) {
            throw prazoDaRequisicaoEsgotado(descricao);
        }
        var observacao = Observacoes.dependencia(observationRegistry, DEPENDENCIA, operacao).start();
        try (var _ = observacao.openScope()) {
            return Optional.of(Objects.requireNonNullElse(chamada.get(), ""));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            observacao.error(e);
            throw e;
        } finally {
            observacao.stop();
        }
    }

    private static TempoEsgotadoNaFonteDeOperacoesException prazoDaRequisicaoEsgotado(String descricao) {
        return new TempoEsgotadoNaFonteDeOperacoesException(
                "Prazo da requisição esgotado antes de consultar " + descricao + " na " + FONTE);
    }

    /** Timeouts de conexão, de obtenção de conexão do pool e de resposta são {@link InterruptedIOException}. */
    private static boolean prazoEsgotado(Throwable erro) {
        for (var causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /** Próxima chamada concluída; sem esperar além do prazo da requisição. */
    private static BlocoLido resultado(ExecutorCompletionService<BlocoLido> concluidas) {
        try {
            var restante = PrazoDaRequisicao.restante();
            if (restante.isEmpty()) {
                return concluidas.take().get();
            }
            var concluida = concluidas.poll(restante.get().toNanos(), TimeUnit.NANOSECONDS);
            if (concluida == null) {
                throw prazoDaRequisicaoEsgotado("os blocos");
            }
            return concluida.get();
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

    /** Conteúdo de um bloco de uma operação; vazio quando o gateway não tem o bloco. */
    private record BlocoLido(long idOperacao, Bloco bloco, Optional<Object> conteudo) {
    }

    /** Item da lista de operações de um cliente ou conta. */
    private record OperacaoListada(long idOperacao, String dataContratacao) {

        static OperacaoListada de(Map<String, Object> item) {
            if (!(item.get(LayoutsTrancode.CAMPO_ID_OPERACAO) instanceof Number id)
                    || !(item.get(LayoutsTrancode.CAMPO_DATA_CONTRATACAO) instanceof String data)) {
                throw new TrancodeInvalidoException("Lista de operações da " + FONTE
                        + " sem id_operacao ou data_contratacao_operacao em um item");
            }
            return new OperacaoListada(id.longValue(), data);
        }
    }
}
