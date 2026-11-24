package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeCustodia;
import br.com.consultas.operacoescredito.application.port.out.CotaDaFonteEsgotadaException;
import br.com.consultas.operacoescredito.application.port.out.CustodiasIndisponiveisException;
import br.com.consultas.operacoescredito.application.port.out.FalhaNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoesIndisponivelException;
import br.com.consultas.operacoescredito.application.port.out.TempoEsgotadoNaFonteDeOperacoesException;
import br.com.consultas.operacoescredito.config.observabilidade.MetricasDeCustodia;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.CriterioConsulta;
import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.OperacaoConsultada;
import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fonte primária (mainframe via gateway, ADR-0002: sem cache de dados). Cada trancode é convertido pelo
 * {@link ParserTrancode} com o layout da custódia e do bloco no mesmo conteúdo que a visão consolidada produz.
 * <ul>
 *     <li>OPERACAO: com a custódia no cache de custódia (ADR-0012), uma chamada por bloco pedido nela; se ela não
 *     tem mais a operação, a custódia é esquecida e refaz-se a descoberta. Na descoberta (etapa
 *     {@value #ETAPA_DESCOBERTA}), o bloco {@code operacao} é pedido nas quatro custódias em paralelo; vale a de
 *     maior precedência entre as que o têm, e os demais blocos são pedidos só nela;</li>
 *     <li>CLIENTE/CONTA: a lista de operações em cada custódia (etapa {@value #ETAPA_LISTA}), todas as páginas,
 *     juntadas e ordenadas (mais recentes primeiro) e cortadas em {@code limite_operacoes}; depois uma chamada por
 *     operação × bloco, cada operação na sua custódia.</li>
 * </ul>
 * Blocos-lista e listas vêm em páginas (ADR-0016), lidas em sequência até a última ou até
 * {@code max-paginas}. As chamadas rodam em paralelo (etapa {@value #ETAPA_FAN_OUT}), em virtual threads com o
 * contexto da requisição (MDC, trace) e no máximo {@code maxChamadasSimultaneas} ao mesmo tempo por requisição.
 * Cada chamada passa pela {@link ResilienciaFontePrimaria} da sua custódia e rota.
 * <p>
 * Falhas (ADR-0013): a de um bloco que não é {@code operacao} o deixa indisponível ({@code null}) e não afeta os
 * outros; a do bloco {@code operacao}, ou de uma custódia sem a qual não se pode afirmar o resultado, é 503
 * ({@link CustodiasIndisponiveisException}). Prazo da requisição esgotado (504) e cota esgotada (429) encerram a
 * consulta inteira e interrompem as chamadas em andamento. Bloco inexistente no gateway (404) vem {@code null};
 * sem o bloco {@code operacao}, a operação não existe naquela custódia.
 */
final class FontePrimariaTrancodeAdapter implements FonteDeOperacoes {

    private static final Logger log = LoggerFactory.getLogger(FontePrimariaTrancodeAdapter.class);

    private static final String FONTE = "fonte primária";
    /** Valor da tag {@code dependencia} nos spans e timers das chamadas ao gateway. */
    static final String DEPENDENCIA = "fonte_primaria";
    /** Valor da tag {@code rota} das métricas de custódia. */
    static final String ROTA = "fonte_primaria";
    static final String ETAPA_FAN_OUT = "fan_out";
    static final String ETAPA_DESCOBERTA = "descoberta_custodia";
    static final String ETAPA_LISTA = "lista_operacoes";
    private static final Duration TENTE_NOVAMENTE_CUSTODIA = Duration.ofSeconds(1);

    /** Mais recentes primeiro por data de contratação; no empate, maior {@code id_operacao} primeiro. */
    private static final Comparator<OperacaoListada> MAIS_RECENTE_PRIMEIRO =
            Comparator.comparing(OperacaoListada::dataContratacao)
                    .thenComparingLong(OperacaoListada::idOperacao)
                    .reversed();

    /** Lista de operações: a chamada do gateway, a rota de resiliência e a tag {@code operacao} da observação. */
    enum Lista {
        CLIENTE("lista_cliente", "lista_operacoes_cliente"),
        CONTA("lista_conta", "lista_operacoes_conta");

        private final String rota;
        private final String operacao;

        Lista(String rota, String operacao) {
            this.rota = rota;
            this.operacao = operacao;
        }

        String rota() {
            return rota;
        }

        String operacao() {
            return operacao;
        }
    }

    private final GatewayMainframeClient gateway;
    private final LayoutsTrancode layouts;
    private final ObservationRegistry observationRegistry;
    private final ResilienciaFontePrimaria resiliencia;
    private final CacheDeCustodia cacheDeCustodia;
    private final PrecedenciaDeCustodia precedencia;
    private final MetricasDeCustodia metricas;
    private final int maxChamadasSimultaneas;
    private final int maxPaginas;
    private final ParserTrancode parser = new ParserTrancode();
    private final ContextSnapshotFactory contexto = ContextSnapshotFactory.builder().build();

    FontePrimariaTrancodeAdapter(GatewayMainframeClient gateway, LayoutsTrancode layouts,
                                 ObservationRegistry observationRegistry, ResilienciaFontePrimaria resiliencia,
                                 CacheDeCustodia cacheDeCustodia, PrecedenciaDeCustodia precedencia,
                                 MetricasDeCustodia metricas, int maxChamadasSimultaneas, int maxPaginas) {
        if (maxChamadasSimultaneas < 1) {
            throw new IllegalArgumentException("maxChamadasSimultaneas deve ser ao menos 1");
        }
        if (maxPaginas < 1) {
            throw new IllegalArgumentException("maxPaginas deve ser ao menos 1");
        }
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.layouts = Objects.requireNonNull(layouts, "layouts");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.resiliencia = Objects.requireNonNull(resiliencia, "resiliencia");
        this.cacheDeCustodia = Objects.requireNonNull(cacheDeCustodia, "cacheDeCustodia");
        this.precedencia = Objects.requireNonNull(precedencia, "precedencia");
        this.metricas = Objects.requireNonNull(metricas, "metricas");
        this.maxChamadasSimultaneas = maxChamadasSimultaneas;
        this.maxPaginas = maxPaginas;
    }

    @Override
    public List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos) {
        try (var chamadas = new Chamadas()) {
            return switch (criterio) {
                case CriterioConsulta.PorOperacao porOperacao ->
                        porOperacao(chamadas, porOperacao.idOperacao(), blocos);
                case CriterioConsulta.PorCliente porCliente -> porLista(chamadas, Lista.CLIENTE,
                        porCliente.cpfCnpj(), porCliente.limiteOperacoes(), blocos);
                case CriterioConsulta.PorConta porConta -> porLista(chamadas, Lista.CONTA,
                        porConta.conta(), porConta.limiteOperacoes(), blocos);
            };
        }
    }

    // ---------------------------------------------------------------- OPERACAO

    private List<OperacaoConsultada> porOperacao(Chamadas chamadas, long idOperacao, Set<Bloco> blocos) {
        var lembrada = cacheDeCustodia.buscar(idOperacao);
        if (lembrada.isPresent()) {
            var custodia = lembrada.get();
            metricas.contar(custodia, MetricasDeCustodia.Resultado.CACHE);
            var lidos = detalhar(chamadas, List.of(new Alvo(idOperacao, custodia)), blocos, false).get(idOperacao);
            var operacao = lidos.get(Bloco.OPERACAO);
            if (operacao instanceof Falhou<Optional<Object>> falha) {
                throw custodiasIndisponiveis(Set.of(custodia), falha.erro());
            }
            if (operacao instanceof Lido<Optional<Object>>(var conteudo) && conteudo.isPresent()) {
                return List.of(montar(idOperacao, custodia, blocos, conteudo.get(), lidos));
            }
            // A custódia lembrada não tem mais a operação (migrou ou deixou de existir): descoberta completa
            log.info("Operação {} não está mais na custódia {} do cache; refazendo a descoberta", idOperacao,
                    custodia.codigo());
            cacheDeCustodia.remover(idOperacao);
        }
        return descobrir(chamadas, idOperacao, blocos);
    }

    private List<OperacaoConsultada> descobrir(Chamadas chamadas, long idOperacao, Set<Bloco> blocos) {
        var tarefas = new EnumMap<Custodia, Callable<Optional<Object>>>(Custodia.class);
        for (var custodia : Custodia.values()) {
            tarefas.put(custodia, () -> lerBloco(chamadas, idOperacao, custodia, Bloco.OPERACAO, false));
        }
        var porCustodia = Observacoes.etapa(observationRegistry, ETAPA_DESCOBERTA)
                .observe(() -> chamadas.todas(tarefas));

        var encontradas = new EnumMap<Custodia, Object>(Custodia.class);
        var falhas = new EnumMap<Custodia, RuntimeException>(Custodia.class);
        porCustodia.forEach((custodia, resultado) -> {
            switch (resultado) {
                case Lido<Optional<Object>>(var conteudo) when conteudo.isPresent() -> {
                    encontradas.put(custodia, conteudo.get());
                    metricas.contar(custodia, MetricasDeCustodia.Resultado.ENCONTRADA);
                }
                case Lido<Optional<Object>> naoEncontrada ->
                        metricas.contar(custodia, MetricasDeCustodia.Resultado.NAO_ENCONTRADA);
                case Falhou<Optional<Object>>(var erro) -> {
                    falhas.put(custodia, erro);
                    metricas.contar(custodia, MetricasDeCustodia.Resultado.INDISPONIVEL);
                    log.warn("Custódia {} indisponível na descoberta da operação {}: {}", custodia.codigo(),
                            idOperacao, erro.toString());
                }
            }
        });
        var vencedora = precedencia.vencedora(encontradas.keySet());
        if (vencedora.isEmpty()) {
            if (!falhas.isEmpty()) {
                // Pode estar justamente na custódia que não respondeu: 503, nunca 404
                throw custodiasIndisponiveis(falhas.keySet(), falhas.values().iterator().next());
            }
            return List.of();
        }
        var custodia = vencedora.get();
        if (encontradas.size() > 1) {
            metricas.duplicada(idOperacao, encontradas.keySet(), custodia);
        }
        cacheDeCustodia.gravar(idOperacao, custodia);
        var demais = EnumSet.noneOf(Bloco.class);
        demais.addAll(blocos);
        demais.remove(Bloco.OPERACAO);
        var lidos = demais.isEmpty()
                ? Map.<Bloco, Resultado<Optional<Object>>>of()
                : detalhar(chamadas, List.of(new Alvo(idOperacao, custodia)), demais, false).get(idOperacao);
        return List.of(montar(idOperacao, custodia, blocos, encontradas.get(custodia), lidos));
    }

    // ---------------------------------------------------------------- CLIENTE / CONTA

    private List<OperacaoConsultada> porLista(Chamadas chamadas, Lista lista, String chave, OptionalInt limite,
                                              Set<Bloco> blocos) {
        var alvos = Observacoes.etapa(observationRegistry, ETAPA_LISTA)
                .observe(() -> listarOperacoes(chamadas, lista, chave, limite));
        if (alvos.isEmpty()) {
            return List.of();
        }
        var lidos = detalhar(chamadas, alvos, blocos, true);
        var operacoes = new ArrayList<OperacaoConsultada>(alvos.size());
        for (var alvo : alvos) {
            var daOperacao = lidos.get(alvo.idOperacao());
            switch (daOperacao.get(Bloco.OPERACAO)) {
                case Falhou<Optional<Object>>(var erro) ->
                        throw custodiasIndisponiveis(Set.of(alvo.custodia()), erro);
                case Lido<Optional<Object>>(var conteudo) when conteudo.isPresent() ->
                        operacoes.add(montar(alvo.idOperacao(), alvo.custodia(), blocos, conteudo.get(), daOperacao));
                // Sem o bloco operacao a operação não existe (ou deixou de existir depois da lista)
                case Lido<Optional<Object>> naoExiste -> {
                }
            }
        }
        return operacoes;
    }

    /**
     * Lê a lista de operações em todas as custódias, todas as páginas, e aplica o limite antes do fan-out: só as
     * operações selecionadas são detalhadas. A lista não vem ordenada; é ordenada aqui (mais recentes primeiro) e
     * operação repetida em mais de uma custódia fica com a de maior precedência. Custódia que falhar torna a lista
     * incompleta: 503. 404 do gateway: nenhuma operação naquela custódia.
     */
    private List<Alvo> listarOperacoes(Chamadas chamadas, Lista lista, String chave, OptionalInt limite) {
        var tarefas = new EnumMap<Custodia, Callable<List<OperacaoListada>>>(Custodia.class);
        for (var custodia : Custodia.values()) {
            tarefas.put(custodia, () -> lerLista(chamadas, lista, custodia, chave));
        }
        var porCustodia = chamadas.todas(tarefas);

        var falhas = new EnumMap<Custodia, RuntimeException>(Custodia.class);
        var custodiasPorId = new HashMap<Long, EnumSet<Custodia>>();
        var listadas = new LinkedHashMap<Long, OperacaoListada>();
        porCustodia.forEach((custodia, resultado) -> {
            switch (resultado) {
                case Falhou<List<OperacaoListada>>(var erro) -> {
                    falhas.put(custodia, erro);
                    metricas.contar(custodia, MetricasDeCustodia.Resultado.INDISPONIVEL);
                    log.warn("Custódia {} indisponível na {}: {}", custodia.codigo(), lista.operacao(),
                            erro.toString());
                }
                case Lido<List<OperacaoListada>>(var operacoes) -> {
                    metricas.contar(custodia, operacoes.isEmpty()
                            ? MetricasDeCustodia.Resultado.NAO_ENCONTRADA
                            : MetricasDeCustodia.Resultado.ENCONTRADA);
                    for (var operacao : operacoes) {
                        custodiasPorId.computeIfAbsent(operacao.idOperacao(), id -> EnumSet.noneOf(Custodia.class))
                                .add(custodia);
                        listadas.putIfAbsent(operacao.idOperacao(), operacao);
                    }
                }
            }
        });
        if (!falhas.isEmpty()) {
            throw custodiasIndisponiveis(falhas.keySet(), falhas.values().iterator().next());
        }
        var ordenadas = new ArrayList<>(listadas.values());
        ordenadas.sort(MAIS_RECENTE_PRIMEIRO);
        var selecionadas = limite.isPresent() && ordenadas.size() > limite.getAsInt()
                ? ordenadas.subList(0, limite.getAsInt())
                : ordenadas;
        var alvos = new ArrayList<Alvo>(selecionadas.size());
        for (var operacao : selecionadas) {
            var custodias = custodiasPorId.get(operacao.idOperacao());
            var custodia = precedencia.vencedora(custodias).orElseThrow();
            if (custodias.size() > 1) {
                metricas.duplicada(operacao.idOperacao(), custodias, custodia);
            }
            alvos.add(new Alvo(operacao.idOperacao(), custodia));
        }
        var custodias = new HashMap<Long, Custodia>();
        alvos.forEach(alvo -> custodias.put(alvo.idOperacao(), alvo.custodia()));
        cacheDeCustodia.gravarTodas(custodias);
        return alvos;
    }

    /** Todas as páginas da lista de operações de uma custódia; 404 na primeira página: nenhuma operação. */
    private List<OperacaoListada> lerLista(Chamadas chamadas, Lista lista, Custodia custodia, String chave) {
        var layout = layouts.listaOperacoes(custodia);
        var descricao = "a lista de operações na custódia " + custodia.codigo();
        var itens = lerPaginas(layout, descricao, continuacao -> chamadas.chamar(custodia, lista.rota(),
                lista.operacao(), descricao, true, () -> switch (lista) {
                    case CLIENTE -> gateway.operacoesDoCliente(custodia.codigo(), chave, continuacao);
                    case CONTA -> gateway.operacoesDaConta(custodia.codigo(), chave, continuacao);
                }));
        if (itens.isEmpty()) {
            return List.of();
        }
        var operacoes = new ArrayList<OperacaoListada>(itens.get().size());
        itens.get().forEach(item -> operacoes.add(OperacaoListada.de(item)));
        return operacoes;
    }

    // ---------------------------------------------------------------- blocos

    /** Fan-out de uma leitura por operação × bloco, cada operação na sua custódia. */
    private Map<Long, Map<Bloco, Resultado<Optional<Object>>>> detalhar(Chamadas chamadas, List<Alvo> alvos,
                                                                        Set<Bloco> blocos, boolean clienteConta) {
        var tarefas = new LinkedHashMap<ChaveDeLeitura, Callable<Optional<Object>>>();
        for (var alvo : alvos) {
            for (var bloco : blocos) {
                tarefas.put(new ChaveDeLeitura(alvo.idOperacao(), bloco),
                        () -> lerBloco(chamadas, alvo.idOperacao(), alvo.custodia(), bloco, clienteConta));
            }
        }
        var resultados = Observacoes.etapa(observationRegistry, ETAPA_FAN_OUT)
                .highCardinalityKeyValue("operacoes", Integer.toString(alvos.size()))
                .observe(() -> chamadas.todas(tarefas));
        var porOperacao = new HashMap<Long, Map<Bloco, Resultado<Optional<Object>>>>();
        alvos.forEach(alvo -> porOperacao.put(alvo.idOperacao(), new EnumMap<>(Bloco.class)));
        resultados.forEach((chave, resultado) -> porOperacao.get(chave.idOperacao()).put(chave.bloco(), resultado));
        return porOperacao;
    }

    /** Um bloco de uma operação numa custódia: uma chamada (bloco-objeto) ou todas as páginas (bloco-lista). */
    private Optional<Object> lerBloco(Chamadas chamadas, long idOperacao, Custodia custodia, Bloco bloco,
                                      boolean clienteConta) {
        var layout = layouts.de(custodia, bloco);
        var descricao = "o bloco " + bloco.nome() + " da operação " + idOperacao + " na custódia " + custodia.codigo();
        if (!layout.paginado()) {
            return chamadas.chamar(custodia, bloco.nome(), bloco.nome(), descricao, clienteConta,
                            () -> gateway.bloco(custodia.codigo(), idOperacao, bloco.nome(), null))
                    .<Object>map(trancode -> parser.lerObjeto(trancode, layout));
        }
        return lerPaginas(layout, descricao, continuacao -> chamadas.chamar(custodia, bloco.nome(), bloco.nome(),
                        descricao, clienteConta,
                        () -> gateway.bloco(custodia.codigo(), idOperacao, bloco.nome(), continuacao)))
                .map(Object.class::cast);
    }

    /**
     * Lê as páginas em sequência até a última (ADR-0016). Vazio se a primeira página não existe (404). Página
     * seguinte inexistente ou mais de {@code max-paginas} páginas: falha (o bloco fica indisponível, nunca
     * truncado).
     */
    private Optional<List<Map<String, Object>>> lerPaginas(LayoutTrancode layout, String descricao,
                                                           Function<String, Optional<String>> pagina) {
        var itens = new ArrayList<Map<String, Object>>();
        String continuacao = null;
        for (int numero = 1; ; numero++) {
            var trancode = pagina.apply(continuacao);
            if (trancode.isEmpty()) {
                if (numero == 1) {
                    return Optional.empty();
                }
                throw new FalhaNaFonteDeOperacoesException("Página " + numero + " de " + descricao
                        + " não encontrada na " + FONTE);
            }
            var lida = parser.lerPagina(trancode.get(), layout);
            itens.addAll(lida.itens());
            if (lida.proxima().isEmpty()) {
                return Optional.of(itens);
            }
            if (numero >= maxPaginas) {
                log.warn("Teto de {} páginas atingido ao ler {} na {}; o conteúdo não é devolvido truncado",
                        maxPaginas, descricao, FONTE);
                throw new FalhaNaFonteDeOperacoesException("Teto de " + maxPaginas + " páginas atingido ao ler "
                        + descricao);
            }
            continuacao = lida.proxima().get();
        }
    }

    /**
     * Operação com o conteúdo de cada bloco pedido: o lido, ou {@code null} se não existe ou não pôde ser lido (bloco
     * indisponível, com log WARN da causa).
     */
    private OperacaoConsultada montar(long idOperacao, Custodia custodia, Set<Bloco> blocos, Object operacao,
                                      Map<Bloco, Resultado<Optional<Object>>> lidos) {
        var conteudo = new EnumMap<Bloco, Object>(Bloco.class);
        for (var bloco : blocos) {
            if (bloco == Bloco.OPERACAO) {
                conteudo.put(bloco, operacao);
                continue;
            }
            switch (lidos.get(bloco)) {
                case Lido<Optional<Object>>(var valor) -> conteudo.put(bloco, valor.orElse(null));
                case Falhou<Optional<Object>>(var erro) -> {
                    log.warn("Bloco {} da operação {} indisponível na custódia {}: {}", bloco.nome(), idOperacao,
                            custodia.codigo(), erro.toString());
                    conteudo.put(bloco, null);
                }
                case null -> conteudo.put(bloco, null);
            }
        }
        return OperacaoConsultada.naCustodia(custodia, conteudo);
    }

    private static CustodiasIndisponiveisException custodiasIndisponiveis(Set<Custodia> custodias,
                                                                          RuntimeException causa) {
        var espera = causa instanceof FonteDeOperacoesIndisponivelException indisponivel
                ? indisponivel.tenteNovamenteEm()
                : TENTE_NOVAMENTE_CUSTODIA;
        return new CustodiasIndisponiveisException(custodias, espera, causa);
    }

    private static TempoEsgotadoNaFonteDeOperacoesException prazoDaRequisicaoEsgotado(String descricao) {
        return new TempoEsgotadoNaFonteDeOperacoesException(
                "Prazo da requisição esgotado antes de consultar " + descricao + " na " + FONTE);
    }

    /** Falhas que encerram a consulta inteira, em vez de só a leitura em que aconteceram. */
    private static boolean encerraAConsulta(Throwable erro) {
        return erro instanceof TempoEsgotadoNaFonteDeOperacoesException
                || erro instanceof CotaDaFonteEsgotadaException
                || erro instanceof CancellationException;
    }

    // ---------------------------------------------------------------- chamadas de uma requisição

    /**
     * As chamadas ao gateway de uma requisição: no máximo {@code maxChamadasSimultaneas} em andamento, nenhuma
     * espera além do prazo, e as leituras em paralelo em virtual threads. Uma falha que
     * {@link #encerraAConsulta encerra a consulta} interrompe as demais leituras; as outras viram o resultado
     * {@link Falhou} da leitura em que aconteceram.
     */
    private final class Chamadas implements AutoCloseable {

        private final Semaphore vagas = new Semaphore(maxChamadasSimultaneas);
        private final AtomicBoolean desistiu = new AtomicBoolean();
        private final ExecutorService executor =
                ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), contexto);

        /** Executa as leituras em paralelo e devolve o resultado de cada uma, na ordem das chaves. */
        <K, T> Map<K, Resultado<T>> todas(Map<K, Callable<T>> leituras) {
            var concluidas = new ExecutorCompletionService<Map.Entry<K, Resultado<T>>>(executor);
            leituras.forEach((chave, leitura) -> concluidas.submit(() -> Map.entry(chave, executar(leitura))));
            var porChave = new HashMap<K, Resultado<T>>();
            try {
                for (int i = 0; i < leituras.size(); i++) {
                    var concluida = proxima(concluidas);
                    porChave.put(concluida.getKey(), concluida.getValue());
                }
            } catch (RuntimeException e) {
                desistiu.set(true);
                // As demais leituras são interrompidas; o circuito ignora as chamadas interrompidas
                executor.shutdownNow();
                throw e;
            }
            var ordenadas = new LinkedHashMap<K, Resultado<T>>();
            leituras.keySet().forEach(chave -> ordenadas.put(chave, porChave.get(chave)));
            return ordenadas;
        }

        private <T> Resultado<T> executar(Callable<T> leitura) {
            try {
                return new Lido<>(leitura.call());
            } catch (RuntimeException e) {
                if (encerraAConsulta(e)) {
                    throw e;
                }
                return new Falhou<>(e);
            } catch (Exception e) {
                return new Falhou<>(new FalhaNaFonteDeOperacoesException("Falha ao consultar a " + FONTE, e));
            }
        }

        /**
         * Uma chamada ao gateway quando houver vaga, protegida pela {@link ResilienciaFontePrimaria} da custódia e da
         * rota; 404 não é erro e vem vazio. O timeout de uma tentativa com prazo ainda restante é lentidão daquela
         * rota (falha da leitura); com o prazo esgotado, é o prazo da requisição (504).
         */
        Optional<String> chamar(Custodia custodia, String rota, String operacao, String descricao,
                                boolean clienteConta, Supplier<String> chamada) {
            aguardarVaga(descricao);
            try {
                if (desistiu.get()) {
                    throw new CancellationException("Consulta à " + FONTE + " encerrada por outra chamada");
                }
                return resiliencia.executar(custodia, rota, clienteConta,
                        () -> tentar(custodia, operacao, descricao, chamada));
            } catch (RestClientException e) {
                var mensagem = "Falha ao consultar " + descricao + " na " + FONTE;
                if (FalhasDoGateway.timeout(e) && !PrazoDaRequisicao.comporta(FalhasDoGateway.FOLGA_DO_PRAZO)) {
                    throw new TempoEsgotadoNaFonteDeOperacoesException(mensagem + ": prazo esgotado", e);
                }
                throw new FalhaNaFonteDeOperacoesException(mensagem, e);
            } finally {
                vagas.release();
            }
        }

        private void aguardarVaga(String descricao) {
            var restante = PrazoDaRequisicao.restante();
            try {
                if (restante.isEmpty()) {
                    vagas.acquire();
                } else if (!vagas.tryAcquire(restante.get().toNanos(), TimeUnit.NANOSECONDS)) {
                    log.warn("Sem vaga entre as {} chamadas simultâneas da requisição até o fim do prazo para "
                            + "consultar {} na {}", maxChamadasSimultaneas, descricao, FONTE);
                    throw prazoDaRequisicaoEsgotado(descricao);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Consulta à " + FONTE + " interrompida");
            }
        }

        /**
         * Uma tentativa, com span e timer de dependência próprios ({@code operacao} = bloco ou lista,
         * {@code custodia}). Não sai se o prazo da requisição já acabou; o timeout de cada tentativa é limitado ao que
         * resta dele (na fábrica de requisições do {@code RestClient}).
         */
        private Optional<String> tentar(Custodia custodia, String operacao, String descricao,
                                        Supplier<String> chamada) {
            if (PrazoDaRequisicao.esgotado()) {
                throw prazoDaRequisicaoEsgotado(descricao);
            }
            var observacao = Observacoes.dependencia(observationRegistry, DEPENDENCIA, operacao)
                    .lowCardinalityKeyValue("custodia", custodia.codigo())
                    .start();
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

        /** Próxima leitura concluída; sem esperar além do prazo da requisição. */
        private <T> T proxima(ExecutorCompletionService<T> concluidas) {
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

        @Override
        public void close() {
            executor.close();
        }
    }

    /** Resultado de uma leitura: o conteúdo lido ou a falha que a impediu. */
    sealed interface Resultado<T> permits Lido, Falhou {
    }

    record Lido<T>(T valor) implements Resultado<T> {
    }

    record Falhou<T>(RuntimeException erro) implements Resultado<T> {
    }

    /** Operação a detalhar e a custódia em que ela está. */
    private record Alvo(long idOperacao, Custodia custodia) {
    }

    private record ChaveDeLeitura(long idOperacao, Bloco bloco) {
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
