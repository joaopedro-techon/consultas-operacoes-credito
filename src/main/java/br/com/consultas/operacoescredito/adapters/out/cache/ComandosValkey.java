package br.com.consultas.operacoescredito.adapters.out.cache;

import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Os comandos que o cache usa, assíncronos: cada chamada só envia o comando e devolve o futuro da resposta, então
 * vários comandos seguidos vão em pipeline pela mesma conexão. Todos são de <b>uma chave</b>, inclusive o script
 * (ADR-0018): funcionam igual em standalone e em cluster mode, sem {@code CROSSSLOT}.
 */
interface ComandosValkey {

    /** {@code HMGET chave campo...}; a lista tem um valor por campo, na mesma ordem, {@code null} para ausente. */
    CompletableFuture<List<byte[]>> hmget(byte[] chave, byte[][] campos);

    /** {@code SET chave valor PX ttl}. */
    CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos);

    /** {@code DEL chave} (uma chave). */
    CompletableFuture<?> del(byte[] chave);

    /**
     * {@code EVALSHA} do script sobre uma chave; se o Valkey ainda não conhece o script ({@code NOSCRIPT}, por
     * exemplo depois de um restart ou failover), {@code EVAL} com o corpo, que o deixa em cache para os próximos.
     */
    CompletableFuture<Long> executar(ScriptValkey script, byte[] chave, byte[]... argumentos);

    /**
     * Comandos sobre a conexão nativa compartilhada do Lettuce ({@code spring.data.redis}, standalone ou cluster).
     * Obter a conexão pode abrir a conexão TCP (limitada por {@code spring.data.redis.connect-timeout}); a resposta
     * de cada comando, pelo timeout de comando do Lettuce ({@code spring.data.redis.timeout}).
     */
    static ComandosValkey lettuce(RedisConnectionFactory conexoes) {
        return new ComandosValkey() {

            @Override
            public CompletableFuture<List<byte[]>> hmget(byte[] chave, byte[][] campos) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).hmget(chave, campos).toCompletableFuture()
                            .thenApply(valores -> valores.stream().map(ComandosValkey::valor).toList());
                }
            }

            @Override
            public CompletableFuture<?> set(byte[] chave, byte[] valor, long ttlMilissegundos) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).set(chave, valor, SetArgs.Builder.px(ttlMilissegundos))
                            .toCompletableFuture();
                }
            }

            @Override
            public CompletableFuture<?> del(byte[] chave) {
                try (var conexao = conexoes.getConnection()) {
                    return nativa(conexao).del(chave).toCompletableFuture();
                }
            }

            @Override
            public CompletableFuture<Long> executar(ScriptValkey script, byte[] chave, byte[]... argumentos) {
                byte[][] chaves = {chave};
                try (var conexao = conexoes.getConnection()) {
                    var comandos = nativa(conexao);
                    CompletableFuture<Long> porSha = comandos.<Long>evalsha(script.sha1(), ScriptOutputType.INTEGER,
                            chaves, argumentos).toCompletableFuture();
                    return porSha.exceptionallyCompose(erro -> {
                        if (causa(erro) instanceof RedisNoScriptException) {
                            try (var outra = conexoes.getConnection()) {
                                return nativa(outra).<Long>eval(script.corpo(), ScriptOutputType.INTEGER, chaves,
                                        argumentos).toCompletableFuture();
                            }
                        }
                        return CompletableFuture.failedFuture(erro);
                    });
                }
            }
        };
    }

    private static Throwable causa(Throwable erro) {
        return erro instanceof CompletionException && erro.getCause() != null ? erro.getCause() : erro;
    }

    private static byte[] valor(KeyValue<byte[], byte[]> chaveValor) {
        return chaveValor.hasValue() ? chaveValor.getValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static RedisClusterAsyncCommands<byte[], byte[]> nativa(RedisConnection conexao) {
        if (conexao.getNativeConnection() instanceof RedisClusterAsyncCommands<?, ?> comandos) {
            return (RedisClusterAsyncCommands<byte[], byte[]>) comandos;
        }
        throw new IllegalStateException("Cache por bloco exige o cliente Lettuce (spring.data.redis)");
    }
}
