package br.com.consultas.operacoescredito.adapters.out.cache;

import br.com.consultas.operacoescredito.domain.Bloco;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Configuração inválida do cache falha na subida (não alcançável pela API HTTP); formato da chave. */
class CachePropertiesTest {

    @Test
    void ttlPositivoEAceito() {
        assertThat(propriedades(Duration.ofMinutes(5)).ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void ttlZeroOuNegativoERejeitado(String ttl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> propriedades(Duration.parse(ttl)))
                .withMessageContaining("consultas.cache.ttl");
    }

    @Test
    void padroesLimitamComandosEConsultasGrandes() {
        var padrao = propriedades(Duration.ofMinutes(5));

        assertThat(padrao.chavesPorComando()).isEqualTo(100);
        assertThat(padrao.maxChavesPorConsulta()).isEqualTo(500);
        assertThat(padrao.prazoMinimoRestante()).isEqualTo(Duration.ofMillis(20));
        assertThat(padrao.maxGravacoesSimultaneas()).isEqualTo(32);
    }

    @Test
    void limitesMenoresQueUmSaoRejeitados() {
        var ttl = Duration.ofMinutes(5);
        var prazo = Duration.ofMillis(20);
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, ttl, 0, 500, prazo, 32))
                .withMessageContaining("chaves-por-comando");
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, ttl, 100, 0, prazo, 32))
                .withMessageContaining("max-chaves-por-consulta");
        assertThatIllegalArgumentException().isThrownBy(() -> new CacheProperties(true, ttl, 100, 500, prazo, 0))
                .withMessageContaining("max-gravacoes-simultaneas");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheProperties(true, ttl, 100, 500, Duration.ofMillis(-1), 32))
                .withMessageContaining("prazo-minimo-restante");
    }

    @Test
    void chaveUsaIdDaOperacaoENomeCanonicoDoBloco() {
        assertThat(new ChaveDeBloco(100000001L, Bloco.CONTROLES_IOF).valor()).isEqualTo("op:100000001:controles_iof");
    }

    static CacheProperties propriedades(Duration ttl) {
        return new CacheProperties(true, ttl, 100, 500, Duration.ofMillis(20), 32);
    }
}
