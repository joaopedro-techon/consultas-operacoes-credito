package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
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

/**
 * Fonte primária: {@code @HttpExchange} sobre {@code RestClient} + Apache HttpClient 5 com pool próprio.
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
                // Uma camada de retry por dependência: a da fonte primária é a do Resilience4j (ticket #14)
                .disableAutomaticRetries()
                .build();
    }

    @Bean(name = FONTE_PRIMARIA, defaultCandidate = false)
    FonteDeOperacoes fontePrimaria(FontePrimariaProperties properties, RestClient.Builder restClientBuilder,
                                   @Qualifier(HTTP_CLIENT) CloseableHttpClient httpClient,
                                   ResourceLoader resourceLoader, ObservationRegistry observationRegistry) {
        Assert.hasText(properties.url(), "consultas.fonte-primaria.url é obrigatório");
        var restClient = restClientBuilder
                .baseUrl(properties.url())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
        var gateway = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(GatewayMainframeClient.class);
        return new FontePrimariaTrancodeAdapter(gateway, LayoutsTrancode.carregar(resourceLoader, properties.layouts()),
                observationRegistry);
    }
}
