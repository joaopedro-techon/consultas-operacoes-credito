package br.com.consultas.operacoescredito.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Locale;
import java.util.Set;

/**
 * Liga o bean só quando a rota está em {@code consultas.rotas-ativas} (padrão: as duas). Cada serviço ECS liga só a
 * sua rota (ADR-0015): {@code visao-consolidada} sem nada do mainframe, {@code fonte-primaria} sem nada do
 * DynamoDB. Quando o mainframe for descontinuado, basta apagar o serviço.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(RotaAtiva.Condicao.class)
public @interface RotaAtiva {

    String PROPRIEDADE = "consultas.rotas-ativas";

    Rota value();

    /** As rotas da API. */
    enum Rota {
        VISAO_CONSOLIDADA, FONTE_PRIMARIA;

        /** Valor em {@value #PROPRIEDADE}, ex.: {@code fonte-primaria}. */
        public String valor() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Lê {@value #PROPRIEDADE} (lista separada por vírgula); ausente, as duas rotas estão ativas. */
    final class Condicao implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var atributos = metadata.getAnnotationAttributes(RotaAtiva.class.getName());
            if (atributos == null) {
                return true;
            }
            var rota = (Rota) atributos.get("value");
            return ativas(Binder.get(context.getEnvironment())).contains(rota.valor());
        }

        static Set<String> ativas(Binder binder) {
            return binder.bind(PROPRIEDADE, Bindable.setOf(String.class))
                    .map(valores -> {
                        var normalizados = new java.util.HashSet<String>();
                        valores.forEach(valor -> normalizados.add(valor.strip().toLowerCase(Locale.ROOT)));
                        return (Set<String>) normalizados;
                    })
                    .orElse(Set.of(Rota.VISAO_CONSOLIDADA.valor(), Rota.FONTE_PRIMARIA.valor()));
        }
    }
}
