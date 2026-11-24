package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.PrecedenciaDeCustodia;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publica a precedência de custódia; uma ordem inválida impede a aplicação de subir. */
@Configuration(proxyBeanMethods = false)
class CustodiaConfig {

    @Bean
    PrecedenciaDeCustodia precedenciaDeCustodia(CustodiaProperties properties) {
        return properties.paraPrecedencia();
    }
}
