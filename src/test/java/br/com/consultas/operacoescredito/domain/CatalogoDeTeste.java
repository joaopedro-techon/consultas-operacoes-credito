package br.com.consultas.operacoescredito.domain;

import br.com.consultas.operacoescredito.domain.catalogo.Campo;
import br.com.consultas.operacoescredito.domain.catalogo.CatalogoDeCampos;
import br.com.consultas.operacoescredito.domain.catalogo.DefinicaoBloco;
import br.com.consultas.operacoescredito.domain.catalogo.EstruturaBloco;
import br.com.consultas.operacoescredito.domain.catalogo.TipoCampo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Catálogo pequeno, montado em código, para os testes de unidade das regras do domínio. */
public final class CatalogoDeTeste {

    private static final Set<Bloco> LISTAS = Set.of(Bloco.PARCELAS, Bloco.COMPONENTES, Bloco.AMORTIZACOES,
            Bloco.RECEBIMENTOS, Bloco.CONTROLES_IOF);

    private CatalogoDeTeste() {
    }

    /**
     * {@code operacao} (situacao_operacao_credito, data_contratacao_operacao), {@code originacao}
     * (dependencia_contabil), {@code parcelas} (numero_parcela_amortizacao, situacao_parcela), {@code saldo}
     * (valor_total, saldo_parcelas[saldo_devedor]); os demais blocos só com {@code id_operacao}.
     */
    public static CatalogoDeCampos catalogo() {
        var definicoes = new ArrayList<DefinicaoBloco>();
        for (var bloco : Bloco.values()) {
            var campos = new ArrayList<Campo>();
            campos.add(Campo.de("id_operacao", TipoCampo.NUMERO));
            switch (bloco) {
                case OPERACAO -> {
                    campos.add(Campo.de("situacao_operacao_credito", TipoCampo.TEXTO));
                    campos.add(Campo.de("data_contratacao_operacao", TipoCampo.DATA));
                }
                case ORIGINACAO -> campos.add(Campo.de("dependencia_contabil", TipoCampo.TEXTO));
                case PARCELAS -> {
                    campos.add(Campo.de("numero_parcela_amortizacao", TipoCampo.NUMERO));
                    campos.add(Campo.de("situacao_parcela", TipoCampo.TEXTO));
                }
                case SALDO -> {
                    campos.add(Campo.de("valor_total", TipoCampo.DECIMAL));
                    campos.add(new Campo("saldo_parcelas", TipoCampo.LISTA, true,
                            List.of(Campo.de("saldo_devedor", TipoCampo.DECIMAL))));
                }
                default -> {
                }
            }
            var estrutura = LISTAS.contains(bloco) ? EstruturaBloco.LISTA : EstruturaBloco.OBJETO;
            definicoes.add(new DefinicaoBloco(bloco, estrutura, campos));
        }
        return CatalogoDeCampos.de(definicoes);
    }
}
