package br.com.consultas.operacoescredito.adapters.out.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Script Lua de uma chave, identificado no Valkey pelo SHA-1 do corpo ({@code EVALSHA}).
 *
 * @param corpo código Lua
 * @param sha1  SHA-1 do corpo, em hexadecimal minúsculo
 */
record ScriptValkey(String corpo, String sha1) {

    /**
     * Gravação de um hash de versão única (ADR-0018). {@code KEYS[1]}: o hash; {@code ARGV[1]}: a versão do registro em
     * epoch ms; {@code ARGV[2]}: o TTL em ms; {@code ARGV[3..]}: pares campo/valor (ao menos um). Compara com o
     * campo {@code versao} que já está no hash:
     * <ul>
     *     <li>ausente (ou fora do formato) ou menor: substitui o hash inteiro e define o TTL ({@code 1});</li>
     *     <li>igual e com {@code marcador} (o invalidador já sabe dessa versão, mas sem os dados): substitui o hash
     *     inteiro e define o TTL ({@code 1});</li>
     *     <li>igual e sem marcador: completa os campos, sem renovar o TTL ({@code 2}), que segue sendo a rede de
     *     segurança para uma invalidação perdida;</li>
     *     <li>maior: rejeita ({@code 0}); quem gravaria leu um dado mais antigo do que o cache já conhece.</li>
     * </ul>
     */
    static final ScriptValkey GRAVAR_VERSIONADO = de("""
            local atual = redis.call('HMGET', KEYS[1], 'versao', 'marcador')
            local versao = tonumber(ARGV[1])
            local versaoAtual = atual[1] and tonumber(atual[1])
            if versaoAtual then
              if versaoAtual > versao then
                return 0
              end
              if versaoAtual == versao and not atual[2] then
                redis.call('HSET', KEYS[1], unpack(ARGV, 3))
                return 2
              end
            end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'versao', ARGV[1], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """);

    /** Resultado do {@link #GRAVAR_VERSIONADO}: rejeitado por haver versão maior no cache. */
    static final long REJEITADO = 0;

    /**
     * Gravação de um hash com versão por bloco (ADR-0019). {@code KEYS[1]}: o hash; {@code ARGV[1]}: o TTL em ms;
     * {@code ARGV[2..]}: trios bloco/versão em epoch ms/valor (ao menos um). Cada bloco é comparado com o seu campo
     * {@code v:{bloco}}, independente dos demais:
     * <ul>
     *     <li>ausente (ou fora do formato), menor, ou igual com {@code m:{bloco}} (o invalidador já sabe dessa versão,
     *     mas sem o dado): grava o bloco e a versão e apaga o marcador;</li>
     *     <li>igual, sem marcador e com o bloco: mantém;</li>
     *     <li>maior: rejeita só esse bloco.</li>
     * </ul>
     * O TTL só é definido quando o hash é criado, nunca renovado: segue sendo a rede de segurança para uma invalidação
     * perdida. Devolve quantos blocos foram gravados ({@code 0}: todos rejeitados ou mantidos).
     */
    static final ScriptValkey GRAVAR_POR_BLOCO = de("""
            local criado = redis.call('EXISTS', KEYS[1]) == 0
            local gravados = 0
            for i = 2, #ARGV, 3 do
              local bloco = ARGV[i]
              local versao = tonumber(ARGV[i + 1])
              local atual = redis.call('HMGET', KEYS[1], 'v:' .. bloco, 'm:' .. bloco, bloco)
              local versaoAtual = atual[1] and tonumber(atual[1])
              local manter = versaoAtual and (versaoAtual > versao
                or (versaoAtual == versao and not atual[2] and atual[3]))
              if not manter then
                redis.call('HSET', KEYS[1], bloco, ARGV[i + 2], 'v:' .. bloco, ARGV[i + 1])
                redis.call('HDEL', KEYS[1], 'm:' .. bloco)
                gravados = gravados + 1
              end
            end
            if criado and gravados > 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return gravados
            """);

    static ScriptValkey de(String corpo) {
        try {
            var sha1 = MessageDigest.getInstance("SHA-1").digest(corpo.getBytes(StandardCharsets.UTF_8));
            return new ScriptValkey(corpo, HexFormat.of().formatHex(sha1));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível na JVM", e);
        }
    }
}
