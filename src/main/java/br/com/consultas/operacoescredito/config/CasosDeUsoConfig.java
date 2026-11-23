package br.com.consultas.operacoescredito.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import br.com.consultas.operacoescredito.application.port.in.ConsultarOperacoesUseCase;
import br.com.consultas.operacoescredito.application.port.out.FonteDeOperacoes;
import br.com.consultas.operacoescredito.application.service.ConsultarOperacoesService;

/** Liga os casos de uso (sem anotações de Spring na camada de aplicação) às portas de saída. */
@Configuration(proxyBeanMethods = false)
class CasosDeUsoConfig {

    @Bean
    ConsultarOperacoesUseCase consultarOperacoesUseCase(FonteDeOperacoes visaoConsolidada) {
        return new ConsultarOperacoesService(visaoConsolidada);
    }
}
