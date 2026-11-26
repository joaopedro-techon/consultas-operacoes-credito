package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.BlocoVersionado;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.BlocosEmCache;
import br.com.consultas.operacoescredito.adapters.out.cache.CacheDeBlocos.GravacaoDeBlocos;
import br.com.consultas.operacoescredito.domain.Bloco;
import br.com.consultas.operacoescredito.domain.VersaoDosBlocos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;

/**
 * Formato do hash de blocos de uma operação numa custódia, pela regra de versão dos blocos. Cada formato tem o seu
 * prefixo de chave, para que trocar a regra nunca misture os dois no mesmo hash.
 * <ul>
 *     <li>{@link VersaoUnica} (ADR-0018): {@code op:{id}:{custodia}}, com {@code versao}, {@code marcador} e um
 *     campo por bloco;</li>
 *     <li>{@link PorBloco} (ADR-0019): {@code opb:{id}:{custodia}}, com {@code {bloco}}, {@code v:{bloco}} e
 *     {@code m:{bloco}} para cada bloco.</li>
 * </ul>
 */
sealed interface FormatoDoHash {

    static FormatoDoHash de(VersaoDosBlocos regra) {
        return switch (regra) {
            case UNICA -> new VersaoUnica();
            case POR_BLOCO -> new PorBloco();
        };
    }

    /** Chave do hash da operação na custódia. */
    byte[] chave(ChaveDeOperacao chave);

    /** Campos do {@code HMGET} dos blocos pedidos, na ordem que {@link #ler} espera. */
    byte[][] campos(List<Bloco> pedidos);

    /**
     * Blocos válidos do hash a partir dos valores do {@code HMGET} de {@link #campos}.
     *
     * @return vazio se o hash não serve (inexistente, com marcador, versão fora do formato)
     */
    Optional<BlocosEmCache> ler(List<byte[]> valores, List<Bloco> pedidos);

    /** Script de gravação do hash. */
    ScriptValkey script();

    /**
     * Argumentos do {@link #script()} para gravar os blocos.
     *
     * @return vazio se o formato não aceita a gravação (versão única com blocos de versões diferentes)
     */
    Optional<byte[][]> argumentos(GravacaoDeBlocos gravacao, byte[] ttl);

    /** Hash de versão única (ADR-0018). */
    record VersaoUnica() implements FormatoDoHash {

        static final String PREFIXO = "op:";
        static final String CAMPO_VERSAO = "versao";
        static final String CAMPO_MARCADOR = "marcador";

        @Override
        public byte[] chave(ChaveDeOperacao chave) {
            return bytes(PREFIXO + chave.idOperacao() + ":" + chave.custodia().codigo());
        }

        @Override
        public byte[][] campos(List<Bloco> pedidos) {
            var campos = new byte[2 + pedidos.size()][];
            campos[0] = bytes(CAMPO_VERSAO);
            campos[1] = bytes(CAMPO_MARCADOR);
            for (int i = 0; i < pedidos.size(); i++) {
                campos[2 + i] = bytes(pedidos.get(i).nome());
            }
            return campos;
        }

        @Override
        public Optional<BlocosEmCache> ler(List<byte[]> valores, List<Bloco> pedidos) {
            var versao = versao(valores.get(0));
            if (versao.isEmpty() || valores.get(1) != null) {
                return Optional.empty();
            }
            var blocos = new EnumMap<Bloco, BlocoVersionado>(Bloco.class);
            for (int i = 0; i < pedidos.size(); i++) {
                var valor = valores.get(2 + i);
                if (valor != null) {
                    blocos.put(pedidos.get(i), new BlocoVersionado(versao.get(), valor));
                }
            }
            return Optional.of(new BlocosEmCache(blocos));
        }

        @Override
        public ScriptValkey script() {
            return ScriptValkey.GRAVAR_VERSIONADO;
        }

        @Override
        public Optional<byte[][]> argumentos(GravacaoDeBlocos gravacao, byte[] ttl) {
            var versao = gravacao.versaoUnica();
            if (versao.isEmpty()) {
                return Optional.empty();
            }
            var argumentos = new ArrayList<byte[]>(2 + 2 * gravacao.blocos().size());
            argumentos.add(bytes(Long.toString(versao.getAsLong())));
            argumentos.add(ttl);
            gravacao.blocos().forEach((bloco, versionado) -> {
                argumentos.add(bytes(bloco.nome()));
                argumentos.add(versionado.textoDetalhe());
            });
            return Optional.of(argumentos.toArray(byte[][]::new));
        }
    }

    /** Hash com versão por bloco (ADR-0019). */
    record PorBloco() implements FormatoDoHash {

        static final String PREFIXO = "opb:";
        static final String PREFIXO_VERSAO = "v:";
        static final String PREFIXO_MARCADOR = "m:";
        /** Campos por bloco no {@code HMGET}: o bloco, a versão e o marcador. */
        private static final int CAMPOS_POR_BLOCO = 3;

        @Override
        public byte[] chave(ChaveDeOperacao chave) {
            return bytes(PREFIXO + chave.idOperacao() + ":" + chave.custodia().codigo());
        }

        @Override
        public byte[][] campos(List<Bloco> pedidos) {
            var campos = new byte[CAMPOS_POR_BLOCO * pedidos.size()][];
            for (int i = 0; i < pedidos.size(); i++) {
                var nome = pedidos.get(i).nome();
                campos[CAMPOS_POR_BLOCO * i] = bytes(nome);
                campos[CAMPOS_POR_BLOCO * i + 1] = bytes(PREFIXO_VERSAO + nome);
                campos[CAMPOS_POR_BLOCO * i + 2] = bytes(PREFIXO_MARCADOR + nome);
            }
            return campos;
        }

        /** Cada bloco vale sozinho: presente, com versão no formato e sem marcador. */
        @Override
        public Optional<BlocosEmCache> ler(List<byte[]> valores, List<Bloco> pedidos) {
            var blocos = new EnumMap<Bloco, BlocoVersionado>(Bloco.class);
            for (int i = 0; i < pedidos.size(); i++) {
                var valor = valores.get(CAMPOS_POR_BLOCO * i);
                var versao = versao(valores.get(CAMPOS_POR_BLOCO * i + 1));
                var marcador = valores.get(CAMPOS_POR_BLOCO * i + 2);
                if (valor != null && versao.isPresent() && marcador == null) {
                    blocos.put(pedidos.get(i), new BlocoVersionado(versao.get(), valor));
                }
            }
            return Optional.of(new BlocosEmCache(blocos));
        }

        @Override
        public ScriptValkey script() {
            return ScriptValkey.GRAVAR_POR_BLOCO;
        }

        @Override
        public Optional<byte[][]> argumentos(GravacaoDeBlocos gravacao, byte[] ttl) {
            var argumentos = new ArrayList<byte[]>(1 + CAMPOS_POR_BLOCO * gravacao.blocos().size());
            argumentos.add(ttl);
            gravacao.blocos().forEach((bloco, versionado) -> {
                argumentos.add(bytes(bloco.nome()));
                argumentos.add(bytes(Long.toString(versionado.versao())));
                argumentos.add(versionado.textoDetalhe());
            });
            return Optional.of(argumentos.toArray(byte[][]::new));
        }
    }

    private static Optional<Long> versao(byte[] valor) {
        if (valor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(new String(valor, StandardCharsets.UTF_8)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }
}
