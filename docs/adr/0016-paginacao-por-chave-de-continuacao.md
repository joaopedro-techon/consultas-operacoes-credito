# Paginação da fonte primária por chave de continuação, sequencial e com teto

O mainframe devolve no máximo 30 ocorrências por chamada. Ele informa só se há mais páginas, não quantas faltam, e entrega uma chave de continuação para pedir a próxima. O trancode de lista passa a ter quantidade (3), indicador de continuação (`S`/`N`) e chave de continuação (tamanho fixo), seguidos das ocorrências. A próxima página é pedida com `?chave_continuacao=`.

Como cada página depende da chave da anterior, a paginação é **sequencial** dentro de um bloco ou lista. O paralelismo continua entre blocos e entre custódias. Cada página é uma chamada completa:
- passa pelo retry, circuito, bulkhead e rate limiter da sua rota (ADR-0014);
- consome a cota (ADR-0015);
- respeita o prazo da requisição.

Há um teto de 20 páginas (600 ocorrências) por bloco ou lista, configurável, para proteger a cota de uma operação ou cliente patológico. Se o teto for atingido, ou se uma página falhar, o bloco fica indisponível (ADR-0013), ou a lista falha com 503. Não se devolve conteúdo truncado.

A lista de operações de um cliente ou conta não vem ordenada. Por isso é preciso ler todas as páginas de todas as custódias antes de ordenar e cortar em `limite_operacoes`: não dá para parar de paginar ao atingir o limite.
