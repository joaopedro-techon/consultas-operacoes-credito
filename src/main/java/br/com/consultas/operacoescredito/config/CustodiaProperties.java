package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.Custodia;
import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Custódias (ADR-0012).
 *
 * @param precedencia ordem que decide a custódia de uma operação encontrada em mais de uma, da maior para a menor;
 *                    deve listar cada custódia exatamente uma vez
 */
@ConfigurationProperties("consultas.custodia")
public record CustodiaProperties(@DefaultValue({"NSF", "SF", "F5", "OD"}) List<Custodia> precedencia) {

    public PrecedenciaDeCustodia paraPrecedencia() {
        return new PrecedenciaDeCustodia(precedencia);
    }
}
