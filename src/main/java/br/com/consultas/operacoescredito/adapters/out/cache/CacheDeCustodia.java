package br.com.consultas.operacoescredito.adapters.out.cache;

/**
 * Cache de custódia (ADR-0012, ADR-0018): em qual custódia cada operação foi encontrada. Guarda só o endereço,
 * nunca dados da operação. Há duas chaves:
 * <ul>
 *     <li>{@link #chaveVersionada(long) custodia-vc:{id_operacao}}: hash da visão consolidada, com a custódia e a
 *     versão do registro. É a única que a visão consolidada lê, e o invalidador grava nela o marcador quando uma
 *     custódia passa a ter a operação;</li>
 *     <li>{@link #chave(long) custodia:{id_operacao}}: só o código da custódia, sem versão. É contrato com o
 *     serviço da fonte primária, que usa o mesmo Valkey (ADR-0017): chave, valor e TTL só mudam de forma
 *     coordenada. A visão consolidada continua gravando-a, mas não a lê.</li>
 * </ul>
 * A leitura e a gravação passam pelo {@link CacheDeBlocos}, na mesma ida ao Valkey dos blocos. Fail-open: falha
 * equivale a "não sei" (refaz a descoberta) e a "não gravado".
 */
public interface CacheDeCustodia {

    /** Esquece a custódia nas duas chaves (a operação não está em nenhuma); best-effort, fora da requisição. */
    void remover(long idOperacao);

    /** Chave compartilhada com a fonte primária, ex.: {@code custodia:100000001}. */
    static String chave(long idOperacao) {
        return "custodia:" + idOperacao;
    }

    /** Chave versionada da visão consolidada, ex.: {@code custodia-vc:100000001}. */
    static String chaveVersionada(long idOperacao) {
        return "custodia-vc:" + idOperacao;
    }
}
