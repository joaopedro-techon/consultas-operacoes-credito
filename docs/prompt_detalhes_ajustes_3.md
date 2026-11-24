# API de Consulta de Operações de Crédito — Custódia de Ativos PF

Este prompt tem como objetivo fazer algumas alterações no que foi entregue até agora:

1 - SK do dynamodb "custodia_expand" nem sempre inicia com SF.

    - A custodia (SF) não é sempre fixa, podendo também ser: SF, F5, OD ou NSF. SF foi apenas o primeiro exemplo porém esqueci de mencionar isso na entrega passada.
    - O usuário não sabe em qual das custodias a operação que ele está buscando está, portanto o sistema precisa verificar em qual das custodias a operacao existe.
    - Cada custodia possui sua propria chamada ao gateway mainframe e cado bloco/dominio (OPERACAO, ORIGINACAO, PARCELAS, AMORTIZACOES, etc) possui sua propria chamada
    - Cada rota do mainframe, inclusive rotas por dominios, devem ter sua propria tratativa de resiliencia e caso a rota para buscar parcelas esteja degradada, nao deve impactar as outras rotas e nem mesmo as outras custodias.
        - Exemplo: Rota do F5 caiu mas isso nao pode impactar o SF, NSF, OD ou o proprio DynamoDB
    - Precisamos ter observabilidade de quais custodias e rotas mais sao chamadas
    - A rota do mainframe é sempre a mesma, o que muda é que passamos o nome da custodia + bloco que queremos buscar o trancode.
    - Cada custodia pode ter um trancode diferente, mas no final tudo deve ser transformado no mesmo contrato, independente se é SF, F5, OD ou NSF. 
        - Algumas custodias podem ter os mesmos campos, campos a mais ou campos a menos.

2 - Chamada ao mainframe
    - As chamadas para o gateway mainframe também precisam incluir a custodia no seu queryParam ou pathVariable:
        - Custodia pode ser SF, F5, NSF ou OD

3 - Adaptar massas de dados no dynamodb para ter massas de mais de uma custodia para conseguirmos testar

    - Exemplo: 
        - SF#EXPAND#AMORTIZACOES
        - F5#EXPAND#AMORTIZACOES
        - NSF#EXPAND#AMORTIZACOES

4 - Incluir logs de warning sempre que acontecer alguma eventualidade do rate limit, retry, bulkhead, circuit breaker, semaphore, etc

    - Quero que sempre que aconteça um retry, tenha um log de warning indicando o retry e o motivo
    - Sempre que o circuir breaker abrir, quero log de warning indicando o motivo da abertura, a mesma coisa vale para o closed e half_open

5 - Hoje está configurado o CONSULTAS_FONTE_PRIMARIA_MAX_CHAMADAS_SIMULTANEAS como 16, porém eu quero avaliar se esse número é suficiente
    
    - Minha aplicação irá rodar em produção em um ECS que terá um autoscaling configurado por quantidade de chamadas no ALB (Application Load Balancer)
    - Eu preciso saber quantas chamadas por segundo uma task consegue aguentar, para assim definir as configuracoes do autoscaling:
        - Exemplo: 1000 chamadas por operacao, 500 chamadas por cliente e 500 chamadas por conta ao mesmo tempo
    - Preciso avaliar se além das CONSULTAS_FONTE_PRIMARIA_MAX_CHAMADAS_SIMULTANEAS as configurados de resiliencia estão de acordo para aguentar a quantidade de chamadas

6 - Avalie conforme o item 5, quanto uma task na AWS ira suportar, inclusive indica a quantidade de VCPU e memoria que a task precisa ter
    
    - Lembre-se que posso crescer horizontamente utilizando o ECS Fargate da AWS
    - A quantidade de VCPU e memoria deve ter um equilibrio entre performance e melhor aproveitamento dos recursos sem gastar dinheiro desnecessariamente
    - O AutoScaling deve ser efetivo e possibilitar termos ate 60 mil chamadas por segundo, sem degradacao e com um servico de qualidade
    - A quantidade de chamadas por segundo pode ser diferente entre a rota do dynamodb e a rota do mainframe

7 - Na rota do mainframe, eu esqueci de mencionar que algumas chamadas sao realizadas de forma paginada, pois o mainframe nao devolve mais do que 30 ocorrencias por vez

    - Quando uma operacao tem 60 parcelas, deve ser feito duas chamadas, uma buscando os 30 primeiros itens e depois os proximos 30
    - A paginacao tambem precisa respeitar as regras de resiliencia e limitacoes da api, de forma que seja efetivo
    - O serviço mainframe suporta até 1500 chamadas por segundo, podendo ser solicitado o aumento para fazer mais chamadas
    - A limitação do mainfrmae nao deve impactar o dynamodb


