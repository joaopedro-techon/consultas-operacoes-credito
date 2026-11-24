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
        assertThat(new CacheProperties(true, Duration.ofMinutes(5)).ttl()).isEqualTo(Duration.ofMinutes(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S"})
    void ttlZeroOuNegativoERejeitado(String ttl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CacheProperties(true, Duration.parse(ttl)))
                .withMessageContaining("consultas.cache.ttl");
    }

    @Test
    void chaveUsaIdDaOperacaoENomeCanonicoDoBloco() {
        assertThat(new ChaveDeBloco(100000001L, Bloco.CONTROLES_IOF).valor()).isEqualTo("op:100000001:controles_iof");
    }
}
