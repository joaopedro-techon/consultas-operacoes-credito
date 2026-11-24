package br.com.consultas.operacoescredito.adapters.out.eventos;

import br.com.consultas.operacoescredito.application.port.out.EventoDeConsumo;
import br.com.consultas.operacoescredito.application.port.out.PublicadorDeEventosDeConsumo;
import br.com.consultas.operacoescredito.config.observabilidade.Observacoes;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.exception.AcquirePermissionCancelledException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Publicação best-effort dos eventos de consumo no SQS (ADR-0004).
 * <ul>
 *     <li>{@link #publicar} só tenta pôr o evento numa fila em memória limitada; cheia, o evento é descartado. A
 *     requisição nunca espera o SQS nem falha por ele.</li>
 *     <li>Uma virtual thread ({@code eventos-consumo-drenador}) monta lotes de até {@code tamanho-lote} eventos,
 *     fechando o lote quando enche ou {@code intervalo-envio} depois do primeiro evento, e despacha cada lote num
 *     {@code SendMessageBatch} em outra virtual thread.</li>
 *     <li>Bulkhead próprio ({@code sqs}) limita os envios simultâneos: sem vaga dentro de {@code max-wait-duration}
 *     o lote é descartado; enquanto espera, a fila em memória segura os eventos novos. Circuit breaker próprio
 *     ({@code sqs}): aberto, o lote é descartado sem chamar o SQS. Timeout e retry são os do SDK.</li>
 *     <li>No encerramento gracioso (depois do servidor web parar de atender), o que restou na fila é enviado em
 *     lotes por até {@code tempo-maximo-drenagem}; o que não couber é descartado.</li>
 * </ul>
 * Métricas: {@code eventos.consumo.enviados}, {@code eventos.consumo.descartados} (tag {@code motivo}) e
 * {@code eventos.consumo.fila} (eventos aguardando envio); cada envio é uma observação
 * {@code consultas.dependencia} ({@code dependencia=sqs}, {@code operacao=SendMessageBatch}).
 */
final class SqsPublicadorDeEventosDeConsumo implements PublicadorDeEventosDeConsumo, SmartLifecycle, AutoCloseable {

    static final String DEPENDENCIA = "sqs";
    static final String OPERACAO = "SendMessageBatch";
    static final String METRICA_ENVIADOS = "eventos.consumo.enviados";
    static final String METRICA_DESCARTADOS = "eventos.consumo.descartados";
    static final String METRICA_FILA = "eventos.consumo.fila";
    /** Para depois do servidor web (graceful shutdown em {@code DEFAULT_PHASE - 1024} e {@code - 2048}). */
    static final int FASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger log = LoggerFactory.getLogger(SqsPublicadorDeEventosDeConsumo.class);
    /** Depois de interromper um envio no fim da drenagem, quanto esperar para ele contar o próprio lote. */
    private static final Duration ESPERA_APOS_INTERROMPER = Duration.ofMillis(100);

    /** Por que um evento foi descartado (tag {@code motivo}). */
    enum Motivo {
        FILA_CHEIA, BULKHEAD_CHEIO, CIRCUITO_ABERTO, FALHA_ENVIO, ENCERRAMENTO;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final SqsClient sqs;
    private final String urlFila;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final ObservationRegistry observationRegistry;
    private final int tamanhoLote;
    private final Duration intervaloEnvio;
    private final Duration tempoMaximoDrenagem;
    private final BlockingQueue<EventoDeConsumo> fila;
    /** Lote que o drenador já tinha montado quando foi interrompido pelo encerramento. */
    private final Queue<List<EventoDeConsumo>> sobras = new ConcurrentLinkedQueue<>();
    private final ExecutorService envios = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("eventos-consumo-envio-", 0).factory());
    private final Counter enviados;
    private final Map<Motivo, Counter> descartados = new EnumMap<>(Motivo.class);

    private volatile boolean rodando;
    private volatile Thread drenador;

    SqsPublicadorDeEventosDeConsumo(SqsClient sqs, EventosSqsProperties properties, CircuitBreaker circuitBreaker,
                                    Bulkhead bulkhead, ObservationRegistry observationRegistry,
                                    MeterRegistry meterRegistry) {
        this.sqs = sqs;
        this.urlFila = properties.sqs().urlFila();
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.observationRegistry = observationRegistry;
        this.tamanhoLote = properties.fila().tamanhoLote();
        this.intervaloEnvio = properties.fila().intervaloEnvio();
        this.tempoMaximoDrenagem = properties.fila().tempoMaximoDrenagem();
        this.fila = new ArrayBlockingQueue<>(properties.fila().capacidade());
        this.enviados = Counter.builder(METRICA_ENVIADOS)
                .description("Eventos de consumo aceitos pelo SQS")
                .register(meterRegistry);
        for (var motivo : Motivo.values()) {
            descartados.put(motivo, Counter.builder(METRICA_DESCARTADOS)
                    .description("Eventos de consumo descartados (best-effort, ADR-0004)")
                    .tag("motivo", motivo.tag())
                    .register(meterRegistry));
        }
        Gauge.builder(METRICA_FILA, fila, BlockingQueue::size)
                .description("Eventos de consumo na fila em memória, aguardando envio")
                .register(meterRegistry);
    }

    @Override
    public void publicar(EventoDeConsumo evento) {
        if (!rodando) {
            descartar(1, Motivo.ENCERRAMENTO);
        } else if (!fila.offer(evento)) {
            descartar(1, Motivo.FILA_CHEIA);
        } else if (!rodando && fila.remove(evento)) {
            // O encerramento começou entre a verificação e o offer e pode já ter esvaziado a fila: quem tirar o
            // evento da fila (aqui ou o encerramento) é quem o conta
            descartar(1, Motivo.ENCERRAMENTO);
        }
    }

    @Override
    public void start() {
        rodando = true;
        drenador = Thread.ofVirtual().name("eventos-consumo-drenador").start(this::drenar);
    }

    /**
     * Para de aceitar eventos e envia o que restou, tudo dentro de {@code tempo-maximo-drenagem}: interrompe o
     * drenador, envia em sequência (numa virtual thread própria, interrompida no limite) o lote que ele montava e
     * o resto da fila, e espera os envios em andamento. Todo evento termina enviado ou contado como descartado:
     * o que não saiu no tempo, inclusive o que chegou à fila durante o encerramento, conta como
     * {@code encerramento}.
     */
    @Override
    public void stop() {
        if (!rodando) {
            return;
        }
        rodando = false;
        var limite = System.nanoTime() + tempoMaximoDrenagem.toNanos();
        drenador.interrupt();
        var pendentes = new ConcurrentLinkedQueue<List<EventoDeConsumo>>();
        try {
            drenador.join(restante(limite));
            pendentes.addAll(lotesRestantes());
            if (!pendentes.isEmpty()) {
                log.info("Encerramento: enviando {} lotes de eventos de consumo ainda na fila", pendentes.size());
                var drenagem = Thread.ofVirtual().name("eventos-consumo-drenagem").start(() -> {
                    for (var lote = pendentes.poll(); lote != null && !Thread.currentThread().isInterrupted();
                         lote = pendentes.poll()) {
                        enviar(lote);
                    }
                });
                if (!drenagem.join(restante(limite))) {
                    log.warn("Encerramento: tempo máximo de drenagem esgotado; eventos de consumo restantes descartados");
                    drenagem.interrupt();
                    // O envio interrompido conta o próprio lote
                    drenagem.join(ESPERA_APOS_INTERROMPER);
                }
            }
            envios.shutdown();
            if (!envios.awaitTermination(restante(limite).toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn("Encerramento: envios de eventos de consumo ainda em andamento foram interrompidos");
                envios.shutdownNow();
                envios.awaitTermination(ESPERA_APOS_INTERROMPER.toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            envios.shutdownNow();
        } finally {
            // O que não chegou a sair: lotes que a drenagem não pegou e eventos que entraram na fila (ou sobraram
            // do drenador) depois da primeira coleta
            pendentes.addAll(lotesRestantes());
            var descartados = 0;
            for (var lote = pendentes.poll(); lote != null; lote = pendentes.poll()) {
                descartados += lote.size();
            }
            descartar(descartados, Motivo.ENCERRAMENTO);
        }
    }

    /** Esvazia as sobras do drenador e a fila, em lotes. */
    private List<List<EventoDeConsumo>> lotesRestantes() {
        var restantes = new ArrayList<EventoDeConsumo>();
        for (var sobra = sobras.poll(); sobra != null; sobra = sobras.poll()) {
            restantes.addAll(sobra);
        }
        fila.drainTo(restantes);
        var lotes = new ArrayList<List<EventoDeConsumo>>();
        for (int inicio = 0; inicio < restantes.size(); inicio += tamanhoLote) {
            lotes.add(List.copyOf(restantes.subList(inicio, Math.min(inicio + tamanhoLote, restantes.size()))));
        }
        return lotes;
    }

    /** Fecha o cliente SQS na destruição do bean (depois do {@link #stop()}). */
    @Override
    public void close() {
        sqs.close();
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }

    @Override
    public int getPhase() {
        return FASE;
    }

    /**
     * Laço do drenador. Uma falha inesperada ao despachar um lote descarta (e conta) só aquele lote: o drenador
     * segue vivo, senão todo evento seguinte acabaria como {@code fila_cheia}.
     */
    private void drenar() {
        while (!Thread.currentThread().isInterrupted()) {
            var lote = new ArrayList<EventoDeConsumo>(tamanhoLote);
            try {
                lote.add(fila.take());
                var limite = System.nanoTime() + intervaloEnvio.toNanos();
                while (lote.size() < tamanhoLote) {
                    var proximo = fila.poll(limite - System.nanoTime(), TimeUnit.NANOSECONDS);
                    if (proximo == null) {
                        break;
                    }
                    lote.add(proximo);
                }
            } catch (InterruptedException e) {
                guardarSobra(lote);
                return;
            }
            try {
                despachar(lote);
            } catch (RuntimeException e) {
                log.error("Falha inesperada ao despachar {} eventos de consumo; lote descartado", lote.size(), e);
                descartar(lote.size(), Motivo.FALHA_ENVIO);
            }
        }
    }

    /**
     * Envia o lote numa virtual thread, se o bulkhead der vaga a tempo. Interrompido pelo encerramento enquanto
     * espera vaga ({@link AcquirePermissionCancelledException}), o lote fica para o encerramento enviar ou contar.
     */
    private void despachar(List<EventoDeConsumo> lote) {
        try {
            bulkhead.acquirePermission();
        } catch (AcquirePermissionCancelledException e) {
            Thread.currentThread().interrupt();
            guardarSobra(lote);
            return;
        } catch (BulkheadFullException e) {
            if (Thread.currentThread().isInterrupted()) {
                guardarSobra(lote);
            } else {
                log.debug("Bulkhead do SQS cheio; {} eventos de consumo descartados", lote.size());
                descartar(lote.size(), Motivo.BULKHEAD_CHEIO);
            }
            return;
        }
        try {
            envios.execute(() -> {
                try {
                    enviar(lote);
                } finally {
                    bulkhead.onComplete();
                }
            });
        } catch (RejectedExecutionException e) {
            bulkhead.releasePermission();
            descartar(lote.size(), Motivo.ENCERRAMENTO);
        }
    }

    /** Um {@code SendMessageBatch} sob o circuit breaker; qualquer falha descarta (e conta) o lote. */
    private void enviar(List<EventoDeConsumo> lote) {
        try {
            var entradas = new ArrayList<SendMessageBatchRequestEntry>(lote.size());
            for (int i = 0; i < lote.size(); i++) {
                entradas.add(SendMessageBatchRequestEntry.builder()
                        .id(Integer.toString(i))
                        .messageBody(EventoDeConsumoJson.serializar(lote.get(i)))
                        .build());
            }
            var requisicao = SendMessageBatchRequest.builder().queueUrl(urlFila).entries(entradas).build();
            SendMessageBatchResponse resposta = circuitBreaker.executeSupplier(() ->
                    Observacoes.dependencia(observationRegistry, DEPENDENCIA, OPERACAO).observe(() ->
                            sqs.sendMessageBatch(requisicao)));
            enviados.increment(resposta.successful().size());
            if (resposta.hasFailed() && !resposta.failed().isEmpty()) {
                log.warn("SQS recusou {} de {} eventos de consumo: {}", resposta.failed().size(), lote.size(),
                        resposta.failed().getFirst().code());
                descartar(resposta.failed().size(), Motivo.FALHA_ENVIO);
            }
        } catch (CallNotPermittedException e) {
            log.debug("Circuit breaker do SQS aberto; {} eventos de consumo descartados", lote.size());
            descartar(lote.size(), Motivo.CIRCUITO_ABERTO);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || e instanceof AbortedException) {
                // Envio cortado pelo fim do tempo de drenagem no encerramento
                descartar(lote.size(), Motivo.ENCERRAMENTO);
            } else {
                log.warn("Falha ao enviar {} eventos de consumo ao SQS: {}", lote.size(), e.toString());
                descartar(lote.size(), Motivo.FALHA_ENVIO);
            }
        }
    }

    private void guardarSobra(List<EventoDeConsumo> lote) {
        if (!lote.isEmpty()) {
            sobras.add(lote);
        }
    }

    private void descartar(int quantidade, Motivo motivo) {
        descartados.get(motivo).increment(quantidade);
    }

    private static Duration restante(long limiteNanos) {
        return Duration.ofNanos(Math.max(1_000_000, limiteNanos - System.nanoTime()));
    }
}
