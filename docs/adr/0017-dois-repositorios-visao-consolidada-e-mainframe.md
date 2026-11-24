---
status: accepted
---

# Dois repositórios: visão consolidada e fonte primária

Substitui o ADR-0015 no desenho de "uma imagem, duas rotas". A cota local por task continua valendo, agora só no serviço do mainframe.

A rota da visão consolidada (DynamoDB) e a rota da fonte primária (mainframe) passam a ser **dois projetos Spring Boot em repositórios separados**:
- `consultas-operacoes-credito`: visão consolidada.
- `consultas-operacoes-credito-mainframe`: fonte primária.

Cada um gera a sua imagem e roda no seu serviço ECS (`visao-consolidada` e `fonte-primaria`).

Na mesma imagem, isolar as rotas exigia `consultas.rotas-ativas`, beans condicionais e dependências mortas em cada serviço. Uma mudança em um lado obrigava a testar e reimplantar o outro. Separados, cada serviço tem o seu ciclo de release, o seu autoscaling e os seus limites, e um não arrisca o outro.

**Para o consumidor nada muda.** Os paths, os parâmetros, o header `x-blocos-indisponiveis` e o corpo continuam os mesmos. O ALB continua separando os serviços por path, e o API Gateway (`infra/api-gateway`) continua neste repositório. O cutover é trocar a imagem do serviço `fonte-primaria`.

**Código comum copiado, não compartilhado.** Domínio, catálogo, filtro, seleção, prazo, observabilidade e evento de consumo são copiados, e cada repositório evolui o seu. O pacote base `br.com.consultas.operacoescredito` é o mesmo nos dois, para que a divergência apareça num diff. O que não pode divergir é verificado, não importado:
- **Contrato HTTP.** O `openapi.yaml` (escrito à mão) e o `catalogo-campos.yml` deste repositório são a fonte da verdade. O CI do mainframe os baixa da `main` daqui e falha se o serviço divergir, em todo PR e num job diário. Mudanças de contrato começam aqui e são alinhadas no mainframe logo em seguida.
- **Cache de custódia (ADR-0012).** Os dois serviços continuam usando o mesmo cluster Valkey e a mesma chave, `custodia:{id_operacao}`, com o valor da custódia e TTL de 24 h. Formato, valores e TTL são contrato entre os dois repositórios e só mudam de forma coordenada. Uma lentidão do Valkey alcança os dois serviços, que seguem fail-open.
- **Evento de consumo.** A mesma fila SQS e o mesmo schema (`docs/eventos/evento-consumo.md`), distinguidos pelo campo `rota`.

**ADRs e glossário.** Este ADR é idêntico nos dois repositórios. Cada repositório remove os ADRs e os termos do glossário que só valem para o outro lado:
- deste repositório saem os ADRs 0009, 0014 e 0016;
- do mainframe saem os ADRs 0001, 0007, 0010 e 0011.

A numeração segue independente a partir de 0018. O repositório do mainframe nasce com o histórico completo deste, para preservar o `blame`.

## Considered Options

- **Biblioteca Maven compartilhada** com o domínio e o contrato: volta a acoplar os releases, porque mudar o catálogo exigiria publicar a lib e reimplantar os dois serviços. Rejeitada.
- **Monorepo multi-módulo**: separa as imagens, mas mantém um só ciclo de PR e CI, e é justamente esse acoplamento que se quer remover. Rejeitada.
- **Valkey ou chaves separados por serviço**: isolamento total, mas o serviço do mainframe perderia as custódias descobertas pela visão consolidada e pagaria mais descobertas contra a cota (ADR-0015). Rejeitada.
- **OpenAPI gerado pelo springdoc**: só refletiria o código de cada lado, sem defini-lo. Rejeitada.
