package br.com.consultas.operacoescredito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ConsultasOperacoesCreditoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsultasOperacoesCreditoApplication.class, args);
    }
}
