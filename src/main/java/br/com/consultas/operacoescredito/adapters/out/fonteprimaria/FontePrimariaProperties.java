package br.com.consultas.operacoescredito.adapters.out.fonteprimaria;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Conexão com a fonte primária (gateway do mainframe).
 *
 * @param url     URL base do gateway
 * @param layouts diretório dos layouts de trancode ({@code <bloco>.yml} e {@code lista_operacoes.yml})
 * @param pool    pool de conexões do Apache HttpClient 5
 * @param fanOut  chamadas paralelas ao gateway em uma mesma requisição
 * @param retry   condição de prazo do retry (tentativas e backoff ficam em
 *                {@code resilience4j.retry.instances.fontePrimaria})
 * @param cota    cota de chamadas por segundo do mainframe e como ela se divide entre as instâncias
 * @param paginacao teto de páginas por bloco-lista ou lista de operações
 */
@ConfigurationProperties("consultas.fonte-primaria")
public record FontePrimariaProperties(
        String url,
        @DefaultValue("classpath:trancode/layouts/") String layouts,
        @DefaultValue Pool pool,
        @DefaultValue FanOut fanOut,
        @DefaultValue Retry retry,
        @DefaultValue Cota cota,
        @DefaultValue Paginacao paginacao) {

    /**
     * Cota de chamadas do mainframe (ADR-0015).
     *
     * @param chamadasPorSegundo cota do mainframe inteiro, somadas todas as instâncias da API
     * @param maxTasks           máximo de instâncias do serviço {@code fonte-primaria}; cada uma recebe
     *                           {@code chamadasPorSegundo / maxTasks}
     */
    public record Cota(@DefaultValue("1500") int chamadasPorSegundo, @DefaultValue("3") int maxTasks) {

        public Cota {
            if (chamadasPorSegundo < 1 || maxTasks < 1 || chamadasPorSegundo < maxTasks) {
                throw new IllegalArgumentException("consultas.fonte-primaria.cota exige chamadas-por-segundo >= "
                        + "max-tasks >= 1");
            }
        }

        /** Cota desta instância. */
        public int porTask() {
            return chamadasPorSegundo / maxTasks;
        }
    }

    /**
     * @param maxPaginas teto de páginas lidas de um bloco-lista ou de uma lista de operações (ADR-0016); atingido,
     *                   o bloco fica indisponível (ou a lista falha), nunca truncado
     */
    public record Paginacao(@DefaultValue("20") int maxPaginas) {

        public Paginacao {
            if (maxPaginas < 1) {
                throw new IllegalArgumentException("consultas.fonte-primaria.paginacao.max-paginas deve ser ao menos 1");
            }
        }
    }

    /**
     * @param maxConexoes          máximo de conexões abertas no total
     * @param maxConexoesPorRota   máximo de conexões abertas para o gateway
     * @param ttl                  tempo de vida de uma conexão
     * @param keepAlive            tempo máximo que uma conexão ociosa é mantida
     * @param timeoutConexao       prazo para abrir a conexão TCP; bem abaixo do menor prazo da requisição (OPERACAO,
     *                             150 ms) para que um host inalcançável estoure este timeout (conta no circuito e é
     *                             retentado) e não o prazo. Como os demais, é limitado ao que resta do prazo
     * @param timeoutObterConexao  prazo para obter uma conexão do pool
     * @param timeoutResposta      prazo para receber a resposta
     */
    public record Pool(
            @DefaultValue("240") int maxConexoes,
            @DefaultValue("240") int maxConexoesPorRota,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("30s") Duration keepAlive,
            @DefaultValue("50ms") Duration timeoutConexao,
            @DefaultValue("50ms") Duration timeoutObterConexao,
            @DefaultValue("700ms") Duration timeoutResposta) {
    }

    /**
     * @param maxChamadasSimultaneas teto de chamadas ao gateway em andamento ao mesmo tempo por requisição
     *                               (operação × bloco); as demais esperam a vez
     */
    public record FanOut(@DefaultValue("16") int maxChamadasSimultaneas) {

        public FanOut {
            if (maxChamadasSimultaneas < 1) {
                throw new IllegalArgumentException(
                        "consultas.fonte-primaria.fan-out.max-chamadas-simultaneas deve ser ao menos 1");
            }
        }
    }

    /**
     * @param prazoMinimoRestante só tenta de novo se ainda restar pelo menos isto do prazo da requisição: cobre o
     *                            backoff (até 30 ms com os valores padrão: 10 ms, ×2, jitter de 50%) e uma
     *                            tentativa rápida
     */
    public record Retry(@DefaultValue("600ms") Duration prazoMinimoRestante) {

        public Retry {
            if (prazoMinimoRestante.isNegative()) {
                throw new IllegalArgumentException(
                        "consultas.fonte-primaria.retry.prazo-minimo-restante não pode ser negativo");
            }
        }
    }
}