package br.com.consultas.operacoescredito.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PrecedenciaDeCustodiaTest {

    @Test
    void nsfVenceTodasAsOutrasNaPrecedenciaPadrao() {
        var precedencia = PrecedenciaDeCustodia.PADRAO;

        assertThat(precedencia.vencedora(Set.of(Custodia.OD, Custodia.SF, Custodia.NSF))).contains(Custodia.NSF);
        assertThat(precedencia.vencedora(Set.of(Custodia.OD, Custodia.F5))).contains(Custodia.F5);
        assertThat(precedencia.vencedora(Set.of(Custodia.SF, Custodia.F5))).contains(Custodia.SF);
        assertThat(precedencia.vencedora(Set.of())).isEmpty();
    }

    @Test
    void ordemConfiguradaPrecisaTerCadaCustodiaUmaVez() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PrecedenciaDeCustodia(List.of(Custodia.NSF, Custodia.SF, Custodia.F5)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new PrecedenciaDeCustodia(List.of(Custodia.NSF, Custodia.SF, Custodia.SF, Custodia.OD)));
    }

    @Test
    void ordemConfiguradaDecideAVencedora() {
        var precedencia = new PrecedenciaDeCustodia(List.of(Custodia.OD, Custodia.F5, Custodia.SF, Custodia.NSF));

        assertThat(precedencia.vencedora(Set.of(Custodia.NSF, Custodia.OD))).contains(Custodia.OD);
    }
}
