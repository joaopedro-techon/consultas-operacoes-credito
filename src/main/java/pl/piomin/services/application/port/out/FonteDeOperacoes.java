package pl.piomin.services.application.port.out;

import pl.piomin.services.domain.Bloco;
import pl.piomin.services.domain.CriterioConsulta;
import pl.piomin.services.domain.OperacaoConsultada;

import java.util.List;
import java.util.Set;

/**
 * Fonte de dados das operações de crédito (visão consolidada ou fonte primária).
 * A rota escolhe qual implementação o caso de uso recebe.
 */
public interface FonteDeOperacoes {

    /**
     * Busca as operações que atendem ao critério, cada uma com os blocos pedidos.
     * Bloco pedido e inexistente na fonte vem com valor {@code null}.
     *
     * @return lista vazia quando nenhuma operação atende ao critério
     */
    List<OperacaoConsultada> buscar(CriterioConsulta criterio, Set<Bloco> blocos);
}
