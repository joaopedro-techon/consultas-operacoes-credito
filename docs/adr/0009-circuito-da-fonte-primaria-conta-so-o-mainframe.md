# Circuito da fonte primária conta só o que é culpa do mainframe

O circuit breaker `fontePrimaria` vê cada tentativa ao gateway, mas várias falhas que chegam até ele são da própria aplicação. Com o prazo da requisição (ADR-0006) limitando conexão, pool e resposta de cada tentativa, uma consulta CLIENTE grande que estoura o prazo (504) tem até 16 chamadas em andamento cujo timeout de socket foi encurtado para o que restava do prazo; contadas, elas abririam o circuito com o mainframe saudável. Por isso, em `FalhasDoGateway`, um timeout que dispara com menos de 10 ms de prazo restante é **ignorado** pelo circuito (o timeout foi do prazo, não do mainframe); só conta o timeout que teve o tempo configurado inteiro. Recusas do rate limiter e do bulkhead (`RequestNotPermitted`, `BulkheadFullException`), lançadas dentro do circuito, também são ignoradas: antes elas contavam como sucesso e diluíam a taxa de falhas. Um 5xx continua contando mesmo com o prazo esgotado.

Para que um host inalcançável seja visto como falha, o timeout de conexão padrão caiu de 200 ms para 50 ms, bem abaixo do menor prazo (OPERACAO, 150 ms), e também é limitado ao prazo restante (`ConnectionConfig` resolvido por requisição). Antes, o prazo acabava primeiro, a chamada era interrompida, o circuito a ignorava e nem o retry de conexão acontecia.

No meio-aberto, o circuito permite 16 chamadas (= `max-chamadas-simultaneas`), e a recusa das chamadas além delas (`CallNotPermittedException` com o circuito meio-aberto) não interrompe o fan-out: as chamadas em andamento são as sondas e terminam (no máximo até o prazo), e só as que esperam vaga desistem. Interrompidas, seriam ignoradas e devolveriam as permissões, e com fan-outs maiores que as permissões o circuito nunca fecharia. A resposta continua sendo 503, mas pode demorar até o fim das sondas.

CLIENTE e CONTA têm um bulkhead próprio na fonte primária (`fontePrimaria-cliente-conta`, 40 chamadas), menor que o total (`fontePrimaria`, 50), como na visão consolidada (ADR-0007): o fan-out das consultas mais caras não toma todas as vagas e sempre sobra lugar para OPERACAO. Aqui o bulkhead vale por chamada (não por consulta), porque o recurso protegido é o pool de conexões do gateway.

## Considered Options

Marcar explicitamente, na fábrica de requisições, a tentativa cujo timeout foi encurtado (atributo no contexto ou `ThreadLocal`) seria mais preciso que olhar o prazo restante na hora do erro, mas espalha estado pela pilha do HTTP client para um ganho marginal: um timeout encurtado sempre dispara com o prazo (quase) esgotado, e um timeout inteiro que coincide com o fim do prazo pode ser ignorado sem prejuízo. Rejeitado.

Só aumentar as chamadas permitidas no meio-aberto até o maior fan-out possível (`limite_operacoes` × blocos, centenas) deixaria o meio-aberto tão longo quanto a janela fechada. Rejeitado em favor de deixar as sondas terminarem.
