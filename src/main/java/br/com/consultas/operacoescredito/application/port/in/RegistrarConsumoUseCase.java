package br.com.consultas.operacoescredito.application.port.in;

/**
 * Registra o consumo de uma requisição como evento de consumo (ADR-0004). Best-effort: não lança exceção, não
 * bloqueia e não espera o envio; o evento pode ser descartado.
 */
@FunctionalInterface
public interface RegistrarConsumoUseCase {

    void registrar(Consumo consumo);
}
