package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.function.Predicate;

/**
 * Fonte primária: {@code @HttpExchange} sobre {@code RestClient} + Apache HttpClient 5 com pool próprio, e
 * resiliência própria ({@link ResilienciaFontePrimaria}), isolada da visão consolidada.
 * Os beans não são candidatos por tipo ({@code defaultCandidate = false}): a injeção de
 * {@link FonteDeOperacoes} sem qualificador continua sendo a visão consolidada, e a rota da fonte
 * primária pede {@value #FONTE_PRIMARIA} explicitamente.
 */
@Configuration(proxyBeanMethods = false)
class FontePrimariaConfig {

    /** Nome (e qualificador) do bean da porta de saída da fonte primária. */
    static final String FONTE_PRIMARIA = "fontePrimaria";
    private static final String HTTP_CLIENT = "fontePrimariaHttpClient";

    @Bean(name = HTTP_CLIENT, defaultCandidate = false)
    CloseableHttpClient fontePrimariaHttpClient(FontePrimariaProperties properties) {
        var pool = properties.pool();
        var conexao = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(pool.timeoutConexao()))
                .setTimeToLive(TimeValue.of(pool.ttl()))
                .build();
        var gerenciador = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(pool.maxConexoes())
                .setMaxConnPerRoute(pool.maxConexoesPorRota())
                .setDefaultConnectionConfig(conexao)
                .build();
        var requisicao = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(pool.timeoutObterConexao()))
                .setResponseTimeout(Timeout.of(pool.timeoutResposta()))
                .build();
        return HttpClients.custom()
                .setConnectionManager(gerenciador)
                .setDefaultRequestConfig(requisicao)
                .setKeepAliveStrategy((resposta, contexto) -> TimeValue.of(pool.keepAlive()))
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.of(pool.keepAlive()))
                // Uma camada de retry por dependência: a da fonte primária é a do Resilience4j
                .disableAutomaticRetries()
                .build();
    }

    /**
     * Retry só para falhas retentáveis ({@link FalhasDoGateway}) e só se o prazo restante da requisição comportar
     * outra tentativa. Tentativas e backoff ficam no {@code application.yml}.
     */
    @Bean
    RetryConfigCustomizer retryDaFontePrimaria(FontePrimariaProperties properties) {
        var prazoMinimo = properties.retry().prazoMinimoRestante();
        Predicate<Throwable> retentavelNoPrazo =
                erro -> FalhasDoGateway.retentavel(erro) && PrazoDaRequisicao.comporta(prazoMinimo);
        return RetryConfigCustomizer.of(ResilienciaFontePrimaria.NOME, retry -> retry.retryOnException(retentavelNoPrazo));
    }

    /** O circuito conta só falhas do mainframe; chamadas interrompidas pelo próprio fan-out não contam. */
    @Bean
    CircuitBreakerConfigCustomizer circuitBreakerDaFontePrimaria() {
        return CircuitBreakerConfigCustomizer.of(ResilienciaFontePrimaria.NOME, circuito -> circuito
                .recordException(FalhasDoGateway::falhaDaDependencia)
                .ignoreException(FalhasDoGateway::ignoradaPeloCircuito));
    }

    @Bean(name = FONTE_PRIMARIA, defaultCandidate = false)
    FonteDeOperacoes fontePrimaria(FontePrimariaProperties properties, RestClient.Builder restClientBuilder,
                                   @Qualifier(HTTP_CLIENT) CloseableHttpClient httpClient,
                                   ResourceLoader resourceLoader, ObservationRegistry observationRegistry,
                                   RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry,
                                   RateLimiterRegistry rateLimiterRegistry, BulkheadRegistry bulkheadRegistry) {
        Assert.hasText(properties.url(), "consultas.fonte-primaria.url é obrigatório");
        var restClient = restClientBuilder
                .baseUrl(properties.url())
                .requestFactory(requisicoesNoPrazo(httpClient, properties.pool()))
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
        var gateway = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GatewayMainframeClient.class);
        var resiliencia = new ResilienciaFontePrimaria(
                retryRegistry.retry(ResilienciaFontePrimaria.NOME),
                circuitBreakerRegistry.circuitBreaker(ResilienciaFontePrimaria.NOME),
                rateLimiterRegistry.rateLimiter(ResilienciaFontePrimaria.NOME),
                bulkheadRegistry.bulkhead(ResilienciaFontePrimaria.NOME));
        return new FontePrimariaTrancodeAdapter(gateway, LayoutsTrancode.carregar(resourceLoader, properties.layouts()),
                observationRegistry, resiliencia, properties.fanOut().maxChamadasSimultaneas());
    }

    /**
     * Fábrica de requisições em que a espera por conexão do pool e pela resposta de cada tentativa nunca passa do
     * que resta do prazo da requisição: o 504 sai no prazo mesmo com o mainframe lento.
     */
    private static HttpComponentsClientHttpRequestFactory requisicoesNoPrazo(CloseableHttpClient httpClient,
                                                                              FontePrimariaProperties.Pool pool) {
        var fabrica = new HttpComponentsClientHttpRequestFactory(httpClient);
        fabrica.setHttpContextFactory((metodo, uri) -> {
            if (PrazoDaRequisicao.restante().isEmpty()) {
                return null;
            }
            var contexto = HttpClientContext.create();
            contexto.setRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.of(PrazoDaRequisicao.limitar(pool.timeoutObterConexao())))
                    .setResponseTimeout(Timeout.of(PrazoDaRequisicao.limitar(pool.timeoutResposta())))
                    .build());
            return contexto;
        });
        return fabrica;
    }
}
