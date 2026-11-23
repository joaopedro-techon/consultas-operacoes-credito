package pl.piomin.services.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.piomin.services.application.port.in.ConsultarOperacoesUseCase;
import pl.piomin.services.application.port.out.FonteDeOperacoes;
import pl.piomin.services.application.service.ConsultarOperacoesService;

/** Liga os casos de uso (sem anotações de Spring na camada de aplicação) às portas de saída. */
@Configuration(proxyBeanMethods = false)
class CasosDeUsoConfig {

    @Bean
    ConsultarOperacoesUseCase consultarOperacoesUseCase(FonteDeOperacoes visaoConsolidada) {
        return new ConsultarOperacoesService(visaoConsolidada);
    }
}
