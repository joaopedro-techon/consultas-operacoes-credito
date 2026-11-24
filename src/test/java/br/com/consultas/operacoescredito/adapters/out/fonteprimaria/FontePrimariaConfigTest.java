package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import br.com.consultas.operacoescredito.config.resiliencia.PrazoDaRequisicao;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Timeout de conexão da fonte primária: abaixo do menor prazo da requisição e encurtado pelo que resta dele.
 * Usa um endereço não roteável (sem resposta ao SYN) para que a conexão só termine pelo timeout; numa rede que
 * recuse o endereço na hora, os testes que dependem do timeout são pulados.
 */
class FontePrimariaConfigTest {

    private static final String INALCANCAVEL = "http://10.255.255.1:81";

    @AfterEach
    void encerrarPrazo() {
        PrazoDaRequisicao.encerrar();
    }

    @Test
    void timeoutDeConexaoPadraoEDoApplicationYmlFicaBemAbaixoDoMenorPrazo() throws Exception {
        var padrao = Binder.get(new StandardEnvironment())
                .bindOrCreate("consultas.fonte-primaria", FontePrimariaProperties.class).pool();
        var fontes = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var configurado = new Binder(ConfigurationPropertySources.from(fontes))
                .bindOrCreate("consultas.fonte-primaria.pool", FontePrimariaProperties.Pool.class);

        for (var pool : new FontePrimariaProperties.Pool[] {padrao, configurado}) {
            assertThat(pool.timeoutConexao()).isEqualTo(Duration.ofMillis(50));
            assertThat(pool.timeoutConexao().multipliedBy(2)).as("duas tentativas cabem no prazo da OPERACAO (150 ms)")
                    .isLessThan(Duration.ofMillis(150));
        }
    }

    @Test
    void timeoutDeConexaoEEncurtadoPeloPrazoRestante() {
        var pool = new FontePrimariaProperties.Pool(50, 50, Duration.ofMinutes(5), Duration.ofSeconds(30),
                Duration.ofSeconds(2), Duration.ofMillis(50), Duration.ofSeconds(1));
        PrazoDaRequisicao.iniciar(Duration.ofMillis(30));

        var configuracao = FontePrimariaConfig.conexaoNoPrazo(pool).resolve(null);

        assertThat(configuracao.getConnectTimeout().toMilliseconds()).isBetween(1L, 30L);
        PrazoDaRequisicao.encerrar();
        assertThat(FontePrimariaConfig.conexaoNoPrazo(pool).resolve(null).getConnectTimeout().toMilliseconds())
                .as("sem prazo vale o configurado").isEqualTo(2000L);
    }

    @Test
    void hostInalcancavelEstouraOTimeoutDeConexaoQueContaNoCircuitoERetenta() throws Exception {
        var chamada = chamarInalcancavel(padrao(), Duration.ofMillis(150));
        var erro = chamada.erro();

        assumeThat(erro).hasRootCauseInstanceOf(ConnectTimeoutException.class);
        assertThat(chamada.duracaoMs()).as("timeout de 50 ms, não o prazo de 150 ms").isLessThan(130);
        assertThat(FalhasDoGateway.falhaDaDependencia(erro)).isTrue();
        assertThat(chamada.ignoradaPeloCircuito()).isFalse();
        assertThat(FalhasDoGateway.retentavel(erro)).isTrue();
    }

    @Test
    void timeoutDeConexaoEncurtadoPeloPrazoEIgnoradoPeloCircuito() throws Exception {
        var propriedades = padrao();
        var pool = propriedades.pool();
        var comConexaoLenta = new FontePrimariaProperties(propriedades.url(), propriedades.layouts(),
                new FontePrimariaProperties.Pool(pool.maxConexoes(), pool.maxConexoesPorRota(), pool.ttl(),
                        pool.keepAlive(), Duration.ofSeconds(2), pool.timeoutObterConexao(), pool.timeoutResposta()),
                propriedades.fanOut(), propriedades.retry());
        var chamada = chamarInalcancavel(comConexaoLenta, Duration.ofMillis(100));
        var erro = chamada.erro();

        assumeThat(erro).hasRootCauseInstanceOf(ConnectTimeoutException.class);
        assertThat(chamada.duracaoMs()).as("timeout de conexão de 2 s cortado no prazo de 100 ms").isLessThan(1000);
        assertThat(chamada.ignoradaPeloCircuito()).isTrue();
    }

    /** Uma chamada de aquecimento sem prazo (carga de classes) e a chamada medida, com o prazo dado. */
    private static Chamada chamarInalcancavel(FontePrimariaProperties propriedades, Duration prazo) throws Exception {
        try (var httpClient = new FontePrimariaConfig().fontePrimariaHttpClient(propriedades)) {
            var restClient = RestClient.builder()
                    .baseUrl(INALCANCAVEL)
                    .requestFactory(FontePrimariaConfig.requisicoesNoPrazo(httpClient, propriedades.pool()))
                    .build();
            PrazoDaRequisicao.iniciar(Duration.ofMillis(50));
            chamar(restClient);
            PrazoDaRequisicao.iniciar(prazo);
            var inicio = System.nanoTime();
            var erro = chamar(restClient);
            // Classificado na hora do erro, como faz o circuito (e não depois de fechar o cliente HTTP)
            var ignorada = FalhasDoGateway.ignoradaPeloCircuito(erro);
            return new Chamada(erro, (System.nanoTime() - inicio) / 1_000_000, ignorada);
        }
    }

    private static ResourceAccessException chamar(RestClient restClient) {
        return catchThrowableOfType(ResourceAccessException.class,
                () -> restClient.get().uri("/mainframe/operacoes/1/blocos/operacao").retrieve().body(String.class));
    }

    private record Chamada(ResourceAccessException erro, long duracaoMs, boolean ignoradaPeloCircuito) {
    }

    private static FontePrimariaProperties padrao() {
        return new FontePrimariaProperties(INALCANCAVEL, "classpath:trancode/layouts/",
                new FontePrimariaProperties.Pool(50, 50, Duration.ofMinutes(5), Duration.ofSeconds(30),
                        Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofSeconds(1)),
                new FontePrimariaProperties.FanOut(16), new FontePrimariaProperties.Retry(Duration.ofMillis(50)));
    }
}
