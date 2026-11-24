---
status: superseded by ADR-0017
---

# Dois serviços ECS e cota do mainframe dividida localmente por task

> Substituído pelo ADR-0017 no desenho de "mesma imagem, propriedade `consultas.rotas-ativas`": cada serviço passa a ter o seu repositório e a sua imagem. A cota local por task, o 429 com `Retry-After` e o deploy com `maximumPercent=100` continuam valendo para o serviço `fonte-primaria`.

A mesma imagem roda como dois serviços ECS Fargate, `visao-consolidada` e `fonte-primaria`. O ALB separa os dois por path (`/v1/operacoes-credito/fonte-primaria*`) e cada serviço escala pelo seu `ALBRequestCountPerTarget`. Uma propriedade (`consultas.rotas-ativas`) liga só a rota daquele serviço.

Motivos para separar:
- **Perfis de carga diferentes.** A rota DynamoDB consome CPU com GZIP, JSON e filtro. A rota mainframe passa a maior parte do tempo esperando I/O (p50 de 220 ms por chamada).
- **Isolamento.** A limitação do mainframe não pode afetar o DynamoDB, e o isolamento entre serviços é mais forte que bulkheads dentro da mesma JVM.
- **Desligamento futuro.** Quando o mainframe for descontinuado, basta apagar o serviço `fonte-primaria`.

O mainframe aceita **1.500 chamadas/s no total** (cota da fonte primária). A vazão do serviço `fonte-primaria` é limitada por essa cota, não pela demanda:
- O serviço tem um **máximo fixo de tasks**, e cada task recebe um rate limiter **local** de `cota ÷ máximo de tasks`.
- O excedente é recusado na hora com **429** e `Retry-After`.
- O deploy usa `maximumPercent=100` e `minimumHealthyPercent=50`. Com o padrão de 200%, o rolling deploy dobraria as tasks e, por alguns minutos, também as chamadas ao mainframe.

O custo desse desenho é que a capacidade fica parada quando o serviço está abaixo do máximo de tasks. Em pico ele está no máximo, e é aí que a cota importa.

## Considered Options

- **Token bucket distribuído em Valkey/Redis (Bucket4j ou script Lua):** coloca uma ida ao cache em cada chamada ao mainframe. Em produção já tivemos o ElastiCache como gargalo de um Bucket4j sob alto volume, aumentando o tempo de resposta. Rejeitado.
- **Hazelcast embarcado com CP Subsystem:**
  - no `awsvpc` não há multicast, então a descoberta precisaria do plugin ECS com permissões IAM;
  - o autoscaling redistribui partições justamente nos picos;
  - um contador consistente exige Raft com pelo menos 3 membros estáveis, e sem ele um split-brain duplica a cota;
  - além disso, consome CPU e heap da própria task.
  Rejeitado.
- **Cota por task ajustada pela contagem de tasks em execução (API do ECS):** mais precisa, mas acrescenta dependência de controle e janelas de inconsistência durante o scale. Fica como evolução, se a capacidade parada se mostrar cara.
