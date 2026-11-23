Eu quero criar uma API para consultar operacoes de credito da Custodia de Ativos PF.

A API irá retornar uma ou mais operacoes de credito do cliente, com dados:
     - operacao: base da operacao, como data de vencimento, cpf_cnpj do cliente, tipo de negociacao, situacao da operacao (ATIVA, LIQUIDADA, CANCELADA), etc
     - parcelas: Parcelas do contrato, com data de vencimento, situacao da oepracao (Aberta, Liquidada, Repactuada)
     - amortizacoes: Lista de amortizacoes realizadas para uma parcela, podendo ter mais de uma ocorrencia por parcela, uma vez que o cliente pode amortizar parcialmente
     - recebimento: Lista de recebimentos de uma determinada operacao de credito. 1 recebimento é a estrutura pai de amortizacao, contendo dados agregados como por exemplo: valor total recebido, motivo da amortizacao, tipo da baixa, etc
     - componentes: Aqui é uma lista de componentes financeiros da operacao, no qual temos o componente de liberacao de credito, onde consta o valor liberado para o cliente, componente de IOF, no qual conta o vlaor de IOF que o cliente deverá pagar, componente de tarifa caso exista, componente com o valor do seguro caso exista, etc
     - plano: Plano de amortização que o cliente fez ao assinar o contrato do emprestimo, contendo as taxas de juros, taxa efetiva total, dia de vencimento das parcelas, taxa de mora, taxa de multa, etc
     - controles de iof: Lista de IOF para determinar quanto o cliente ja pagou de iof para cada parcela. 1 parcela pode ter 1 ou mais ocorrencias de controle de IOF
     - saldo: Saldo devedor de todas as parcelas e também o valor agregado


A ideia é ter uma consulta com uma rota unica, no qual a consulta poderá entregar informacoes de acordo com o queryParam "expand" informado na request:

Seguem os expands disponiveis:
    - operacao
    - originacao
    - componente
    - plano
    - saldo
    - parcela
    - amortizacao
    - recebimento

Observação: 
    - Caso o usuário não informa nenhum expand, deverá sempre retornar os expands operacao e originacao
    - Mesmo o usuário passando, por exemplo, apenas o expand parcela, deverá ser retornado obrigatoriamente o expand de operacao e originacao

Exemplo de chamada: GET /operacoes_credito?id_operacao=123456789&expand=parcela,amortizacao,plano

No header o usuario escolhe o tipo de consulta através do header x-tipo-consulta:

    - OPERACAO: Consulta uma operacao através do número da operacao (id_operacao)
    - CLIENTE: Consulta uma ou mais operaçoes de uma determinado cliente através do CPF_CNPJ
    - CONTA: Consulta uma ou mais operações de um determinado cliente através do número da conta do cliente

A API deverá consultar os dados no DynamoDB na qual seguem uma estrutura de coluna da seguinte forma, conforme documento:

./estrutura_tabela_dynamodb_docs.md


- O Dynamodb é uma estrategia para entregarmos os dados de forma rapida e segura, podendo ser eventualmente consistente
- Também teremos uma outra rota na API para quem deseja buscar os dados diretamente da origem, pois o DynamoDB é uma visão de uma base DB2 no mainframe. A visão do dynamodb já está estrategimanete bem elaborada para maior performance na leitura do dado
- Não se preocupe em como os dados saem do mainframe DB2 e vem para o DynamoDB. Se preocupe apenas se a modelagem do dynamodb está ok e otimizada
- Seguem detalhes das rotas:

    - O projeto terá uma rota GET para consultar o Dynamodb:

    Exemplo: /operacoes

    - O projeto terá uma segunda rota GET para consultar os mesmos dados, porém irá buscar os dados do mainframe através de um API gateway que realiza toda abstração.

    Exemplo: /operacoes_credito

        - Você não precisa se preocupar como os dados estão no mainframe, você irá chamar uma API e ela irá retornar para você uma string que chamamos de trancode. Trancode é uma string no qual cada posição representa uma informação. 
        - Exemplo de trancode para montar o dóminio (expand) de operacao: "123456789ATIVA2026-11-21          12458965432"
            Cada posição representa um valor, o java deve ler essa string e montar o json da mesma forma que o DynamoDB devolve. No final essa string (trancode) será transformada em:
            {
                "id_operacao": 123456789,
                "situacao_operacao_credito": "ATIVA",
                "data_contratacao_operacao": "2026-11-21",
                "data_baixa_operacao": null,
                "cpf_cnpj": "12458965432"
            }
        - Observação: Não existe um padrao de trancode hoje, você pode inventar voce mesmo massas de dados da chamada ao mainframe de forma que fique facil futuramente alterar o template do trancode.
        - Todas as chamadas as apis do mainframe serão virtualizadas no docker-compose com o uso do wiremock


# Requisitos funcionais:
    - O cliente pode buscar os dados de uma operacao especeifica
    - O cliente usar a api para buscar uma lista de operacoes por CPF
    - O cliente pode usar a api para buscar uma lista de operacoes por CONTA
    - Observação: Hoje não existe paginação portanto buscar por CPF ou CONTA retornam todos os dados, podendo aumentar a latencia da API
    - O cliente da api pode passar um ou mais expands para buscar dados que faça sentido ver
    - O cliente pode escolher quais dados precisa ver do retorno, por exemplo: Cliente quer ver, dentro do expand de operacao, apenas a data de contratação, portanto a api so deverá retornar por exmeplo {"data_contratacao_operacao": "2026-11-23"}
    - O cliente pode filtrar quais dados deseja ver, Exemplo:
        - Cliente quer verificar apenas as parcelas que seja do numero >= 4, então o retorno deve ser:

             [
                {
                    "id_operacao": 123456789,
                    "numero_parcela_amortizacao": 4,
                    "numero_plano_amortizacao": 1,
                    "data_vencimento_parcela": "2027-03-21",
                    "valor_principal_parcela": 12.5,
                    "valor_juros_principal_parcela": 10.0,
                    "situacao_parcela": "ABERTA"
                },
                {
                    "id_operacao": 123456789,
                    "numero_parcela_amortizacao": 5,
                    "numero_plano_amortizacao": 1,
                    "data_vencimento_parcela": "2027-04-21",
                    "valor_principal_parcela": 12.5,
                    "valor_juros_principal_parcela": 10.0,
                    "situacao_parcela": "ABERTA"
                }
            ]
    - Os dados de operacao e originacao sempre sao obrigatorios. Caso o usuario afirme no filtro que nao quer ver nenhum dado de OPERACAO ou originacao, deve ser retornado o objeto apenas com o id_operacao. Exemplo:
        -   {
                "id_operacao": 123456789
            }
    - Deverá ter observabilidade de quais usuarios/clientes da api mais fazem chamadas, tempo de resposta, quais dados mais sao consultados, quais expands sao mais solicitados, erros e sucessos.
    - Deverá ser utilizado uma estrutura de dados que seja sustentavel ao longo do tempo

# Requisitos nao funcionais:
    - Consultas por OPERACAO devem ser entregues com um P99 de 80ms
    - Consultas por CLIENTE devem ser entregues com um P99 de 200ms
    - Consultas por CONTA devem ser entregues com um P99 de 200ms
    - Observacao, como nao existirá paginação na primeira entrega da API, então pode ser que o P99 nem sempre seja respeitado, nesses casos deverá falhar rapido, exibindo esse erro para o cliente
        - Para tratar tais problemas consultas por cliente ou conta poderá receber via queryParam o número máximo de operações que deseja no retorno, de forma a minimizar problemas para o cliente
        - Também na mensagem de erro que devolvemos, podemos informar que o cliente pode passar menos expand e depois usar a consulta por operacao para buscar detalhes do contrato/operacao.
    - Deverá ter observabildiade com métricas de baixa cardinalidade, traces e spans que ajudam e contribui na resolução de problemas, logs precisos e padronizados, com MDC e cuidado ao usar virtuais threads para repassar o contexto pai
    - Deverá receber no header o parametro x-correlationid de forma a contribuir para o trace repassando o correlationid para apis upstream e também correlacionando logs
    - Deverá utilizar virtuais threads sempre que possível, uma vez que a aplicação se baseis mais em I/O Bound.
    - Deverá utilizar java 25 e spring boot 4.x.x
    - O codigo deve evitar uso de if/else em excesso, priorizando sempre boas praticas de programação do clean code e design patterns (Sempre lembrar de usar as skills  "spring-boot-template:design-patterns" e "spring-boot-template:java-modern")
    - O codigo deve prezar pela simplicidade e legibilidade dos desenvolvedores, de forma que facilite o code review e também ajude na manutenção
    - Tanto as chamadas para o Dynamodb quanto as chamadas HTTP para o serviço externo que devolve os dados do mainframe precisam ter resiliência e seguir as seguintes diretrizes:
        - Circuit Breaker
        - Time Limiter e aplicabilidade de fail fast
        - Rate Limit para não derrubar nem o dynamodb e nem a api upstream do mainframe
        - Bulkhead: Chamadas excessivas ou erros no mainframe nao devem afetar as chamadas para a api do dynamodb. Uma chamada de um cliente ou operação nao pode afetar e impossibilidade uma outra requisição saudavel de funcionar
        - Tanto o DynamoDB quanto a api que será usada para fazer chamadas HTTP devem ser efetivas, com pool de conexoes que otimizem as consultas
        - Pool de Conexões
        - Retry inteligente com backoff exponencial e jitter
    - O projeto deve possuir cache com redis/valk. O cache deve garantir que se uma chamada com o expand "parcela,amortizacao" for realizada e posteriormente uma chamada apenas com o expand parcela for chamado, o sistema busque no dynamodb ou na api mainframe apenas os dados de amortizacao, uma vez que parcelas já se encontra em cache. O redis pode e deve ser virtualizado com docker para o desenvolvimento.
    - O projeto deve ficar atrás de um API Gateway da AWS, no qual o api gateway deve fazer o uso do Usage Plans para controle de rate limit e devolver 429 caso o cliente faça mais chamadas do que o permitido por segundo.
        - Observação: Todos os componentes da aws serão virtualizados com docker + localstack
    - Utilizar obrigatoriamente Clean Architecture ou Hexagonal ou ambos de modo a deixar o projeto organizado.

# docker-compose

    - O projeto deve utilizar o localstack para simular serviços da AWS
    - o projeto deve utilizar o localstack para simular o DynamoDB + GSI
    - O projeto deve utilizar o localstack para simular o API Gateway da AWS inclusive com UsagePlans para controle de rate limit
    - As chamadas ao gateway do mainframe devem devem ser mockados com wiremock

# Observabilidade
    - O projeto deve publicar métricas e traces sempre que possível, pensando também em finops
    - Logs devem ser padronizados e nao devem atrapalhar a performance da aplicação
    - Aplicar correlationid e qualquer outra informação antes do processamento de qualquer requisicao. Utilize anotacoes do java/spring ou crie interceptors, de forma a nao poluir o codigo central
    - Para este projeto iremos utilizar prometheus, jaeger e grafana, porém o projeto deve suportar futuramente o provider do datadog (Não precisamos de nada do datadog ainda, aqui é somente uma alternativa para o futuro )
    

