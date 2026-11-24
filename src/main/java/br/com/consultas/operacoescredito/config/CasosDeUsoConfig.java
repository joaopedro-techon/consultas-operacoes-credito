package br.com.consultas.operacoescredito.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.port.out.ObservadorDeEtapas;
import br.com.consultas.operacoescredito.application.service.ConsultarOperacoesService;
import br.com.consultas.operacoescredito.config.observabilidade.ObservadorDeEtapasMicrometer;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;

/**
 * Liga os casos de uso (sem anotações de Spring na camada de aplicação) às portas de saída.
 * O mesmo caso de uso atende as duas rotas; muda só a fonte injetada (Strategy). O da fonte primária
 * não é candidato por tipo: quem o quer pede o qualificador {@value #FONTE_PRIMARIA}.
 */
@Configuration(proxyBeanMethods = false)
class CasosDeUsoConfig {

    /** Qualificador do caso de uso e da porta de saída da fonte primária. */
    static final String FONTE_PRIMARIA = "fontePrimaria";

    @Bean
    ObservadorDeEtapas observadorDeEtapas(ObservationRegistry observationRegistry) {
        return new ObservadorDeEtapasMicrometer(observationRegistry);
    }

    @Bean
    ConsultarOperacoesUseCase consultarOperacoesUseCase(FonteDeOperacoes visaoConsolidada,
                                                        CatalogoDeCampos catalogoDeCampos,
                                                        ObservadorDeEtapas observadorDeEtapas) {
        return new ConsultarOperacoesService(visaoConsolidada, catalogoDeCampos, observadorDeEtapas);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(FONTE_PRIMARIA)
    ConsultarOperacoesUseCase consultarOperacoesFontePrimariaUseCase(
            @Qualifier(FONTE_PRIMARIA) FonteDeOperacoes fontePrimaria, CatalogoDeCampos catalogoDeCampos,
            ObservadorDeEtapas observadorDeEtapas) {
        return new ConsultarOperacoesService(fontePrimaria, catalogoDeCampos, observadorDeEtapas);
    }
}
