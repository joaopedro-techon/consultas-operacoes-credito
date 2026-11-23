package br.com.consultas.operacoescredito.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.service.ConsultarOperacoesService;

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
    ConsultarOperacoesUseCase consultarOperacoesUseCase(FonteDeOperacoes visaoConsolidada) {
        return new ConsultarOperacoesService(visaoConsolidada);
    }

    @Bean(defaultCandidate = false)
    @Qualifier(FONTE_PRIMARIA)
    ConsultarOperacoesUseCase consultarOperacoesFontePrimariaUseCase(
            @Qualifier(FONTE_PRIMARIA) FonteDeOperacoes fontePrimaria) {
        return new ConsultarOperacoesService(fontePrimaria);
    }
}
