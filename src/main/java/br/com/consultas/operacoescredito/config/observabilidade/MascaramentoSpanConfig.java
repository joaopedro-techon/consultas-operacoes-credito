package br.com.consultas.operacoescredito.config.observabilidade;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Troca a lista de exportadores de span do Spring Boot ({@link SpanExporters}, que alimenta o
 * {@code BatchSpanProcessor}) pela dos mesmos exportadores envolvidos em {@link MascaramentoSpanExporter}: todo
 * span sai da aplicação com CPF/CNPJ e conta mascarados, inclusive nos eventos de exceção e no status. Os beans
 * {@link SpanExporter} continuam os originais (o OTLP, ou o exportador em memória dos testes).
 */
@Configuration(proxyBeanMethods = false)
class MascaramentoSpanConfig {

    @Bean
    SpanExporters spanExporters(ObjectProvider<SpanExporter> exportadores) {
        return SpanExporters.of(exportadores.orderedStream()
                .map(MascaramentoSpanExporter::new)
                .toList());
    }
}
