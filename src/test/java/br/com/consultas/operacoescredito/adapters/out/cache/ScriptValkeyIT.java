package br.com.consultas.operacoescredito.adapters.out.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regra de versão do {@link ScriptValkey#GRAVAR_VERSIONADO} contra um Valkey de verdade (mesma imagem do
 * docker-compose), pelo {@link ComandosValkey#lettuce cliente da aplicação}: inclusive o cenário que motivou o
 * ADR-0018, a leitura atrasada que tenta regravar o dado antigo logo depois da invalidação.
 */
class ScriptValkeyIT {

    private static final String CHAVE = "op:1:SF";
    private static final long V1 = 1_764_000_000_000L;
    private static final long V2 = V1 + 1;
    private static final long TTL = Duration.ofMinutes(5).toMillis();

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory conexoes;
    private static ComandosValkey comandos;
    private static RedisClient cliente;
    private static StatefulRedisConnection<String, String> valkey;

    @BeforeAll
    static void iniciar() {
        container = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0.8-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        container.start();
        conexoes = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)));
        conexoes.afterPropertiesSet();
        conexoes.start();
        comandos = ComandosValkey.lettuce(conexoes);
        cliente = RedisClient.create("redis://" + container.getHost() + ":" + container.getMappedPort(6379));
        valkey = cliente.connect(StringCodec.UTF8);
    }

    @AfterAll
    static void encerrar() {
        valkey.close();
        cliente.shutdown();
        conexoes.destroy();
        container.stop();
    }

    @BeforeEach
    void limpar() {
        valkey.sync().flushall();
    }

    @Test
    void hashAusenteEGravadoComVersaoETtl() {
        assertThat(gravar(V1, "parcelas", "p1")).isEqualTo(1);

        assertThat(valkey.sync().hgetall(CHAVE)).containsExactlyInAnyOrderEntriesOf(
                Map.of("versao", Long.toString(V1), "parcelas", "p1"));
        assertThat(valkey.sync().pttl(CHAVE)).isBetween(TTL - 5_000, TTL);
    }

    @Test
    void leituraAtrasadaComDadoAntigoNaoSobrescreveOMarcadorDeInvalidacao() {
        gravar(V1, "parcelas", "p1");
        // O invalidador soube da V2: tira os blocos antigos de circulação, mas guarda a versão
        valkey.sync().del(CHAVE);
        valkey.sync().hset(CHAVE, Map.of("versao", Long.toString(V2), "marcador", "1"));
        valkey.sync().pexpire(CHAVE, TTL);

        // Leitura que começou antes da atualização chega 1 ms depois, com a V1
        assertThat(gravar(V1, "parcelas", "p1")).isEqualTo(ScriptValkey.REJEITADO);

        assertThat(valkey.sync().hgetall(CHAVE)).containsExactlyInAnyOrderEntriesOf(
                Map.of("versao", Long.toString(V2), "marcador", "1"));
    }

    @Test
    void leituraNaVersaoDoMarcadorSubstituiOHashInteiro() {
        valkey.sync().hset(CHAVE, Map.of("versao", Long.toString(V2), "marcador", "1"));

        assertThat(gravar(V2, "parcelas", "p2")).isEqualTo(1);

        assertThat(valkey.sync().hgetall(CHAVE)).containsExactlyInAnyOrderEntriesOf(
                Map.of("versao", Long.toString(V2), "parcelas", "p2"));
        assertThat(valkey.sync().pttl(CHAVE)).isBetween(TTL - 5_000, TTL);
    }

    @Test
    void mesmaVersaoCompletaOsBlocosSemRenovarOTtl() {
        gravar(V1, "parcelas", "p1");
        valkey.sync().pexpire(CHAVE, 10_000);

        assertThat(gravar(V1, "saldo", "s1")).isEqualTo(2);

        assertThat(valkey.sync().hgetall(CHAVE)).containsExactlyInAnyOrderEntriesOf(
                Map.of("versao", Long.toString(V1), "parcelas", "p1", "saldo", "s1"));
        assertThat(valkey.sync().pttl(CHAVE)).isLessThanOrEqualTo(10_000);
    }

    @Test
    void versaoMaiorSubstituiOHashInteiroSemMisturarBlocosDaVersaoAnterior() {
        gravar(V1, "parcelas", "p1");
        valkey.sync().hset(CHAVE, "saldo", "s1");

        assertThat(gravar(V2, "parcelas", "p2")).isEqualTo(1);

        assertThat(valkey.sync().hgetall(CHAVE)).containsExactlyInAnyOrderEntriesOf(
                Map.of("versao", Long.toString(V2), "parcelas", "p2"));
    }

    @Test
    void scriptDesconhecidoPeloValkeyEEnviadoComOCorpo() {
        valkey.sync().scriptFlush();

        assertThat(gravar(V1, "parcelas", "p1")).isEqualTo(1);
        assertThat(valkey.sync().scriptExists(ScriptValkey.GRAVAR_VERSIONADO.sha1())).containsExactly(true);
    }

    @Test
    void hmgetDevolveUmValorPorCampoNaOrdemPedida() {
        gravar(V1, "parcelas", "p1");

        var valores = comandos.hmget(bytes(CHAVE), new byte[][]{bytes("versao"), bytes("marcador"),
                bytes("parcelas")}).join();

        assertThat(valores).hasSize(3);
        assertThat(new String(valores.get(0), StandardCharsets.UTF_8)).isEqualTo(Long.toString(V1));
        assertThat(valores.get(1)).isNull();
        assertThat(new String(valores.get(2), StandardCharsets.UTF_8)).isEqualTo("p1");
    }

    private static long gravar(long versao, String campo, String valor) {
        return comandos.executar(ScriptValkey.GRAVAR_VERSIONADO, bytes(CHAVE), bytes(Long.toString(versao)),
                bytes(Long.toString(TTL)), bytes(campo), bytes(valor)).join();
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }
}
