package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.application.port.in.RegistrarConsumoUseCase;
import br.com.consultas.operacoescredito.application.port.out.PublicadorDeEventosDeConsumo;
import br.com.consultas.operacoescredito.application.service.RegistrarConsumoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

/** Caso de uso do evento de consumo; com {@code consultas.eventos.habilitado=false}, não registra nada. */
@Configuration(proxyBeanMethods = false)
class EventosDeConsumoConfig {

    private static final Logger log = LoggerFactory.getLogger(EventosDeConsumoConfig.class);

    @Bean
    RegistrarConsumoUseCase registrarConsumoUseCase(EventosDeConsumoProperties properties,
                                                    ObjectProvider<PublicadorDeEventosDeConsumo> publicador) {
        if (!properties.habilitado()) {
            log.info("Evento de consumo desligado (consultas.eventos.habilitado=false)");
            return consumo -> {
                // desligado
            };
        }
        return new RegistrarConsumoService(publicador.getObject(),
                properties.segredoHmac().getBytes(StandardCharsets.UTF_8));
    }
}
