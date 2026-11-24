package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publica o catálogo de campos; um catálogo inconsistente impede a aplicação de subir. */
@Configuration(proxyBeanMethods = false)
class CatalogoDeCamposConfig {

    @Bean
    CatalogoDeCampos catalogoDeCampos(CatalogoDeCamposProperties properties) {
        return properties.paraCatalogo();
    }
}
