package br.com.consultas.operacoescredito.config;

import br.com.consultas.operacoescredito.domain.VersaoDosBlocos;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Regras da visão consolidada que valem para a leitura no DynamoDB e para o cache.
 *
 * @param versaoDosBlocos regra de versão dos blocos (ADR-0019). {@code UNICA} exige que os escritores (Atualização
 *                        Online e Offline) regravem todos os blocos da operação a cada atualização: com escritores
 *                        que gravam só os blocos alterados, toda operação atualizada parcialmente vira 503.
 *                        {@code POR_BLOCO} funciona com os dois tipos de escritor
 */
@ConfigurationProperties("consultas.visao-consolidada")
public record VisaoConsolidadaProperties(@DefaultValue("POR_BLOCO") VersaoDosBlocos versaoDosBlocos) {
}
