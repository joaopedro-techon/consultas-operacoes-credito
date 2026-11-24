# Resposta parcial por bloco na fonte primária

Até a v1.3.0, a fonte primária não tinha resposta parcial: a primeira falha de qualquer bloco cancelava o fan-out e a consulta respondia 503. Com resiliência isolada por custódia × bloco (ADR-0014, hoje só no repositório do mainframe), isso anularia o isolamento, porque uma rota de parcelas degradada continuaria derrubando a consulta inteira.

Agora vale o seguinte:
- **Um bloco que falha** (circuito aberto, erro depois do retry, página que falhou, teto de páginas) vira **bloco indisponível**. Ele volta `null` e é listado em `x-blocos-indisponiveis`, o mesmo header que a visão consolidada já usava. A resposta é 200, com os outros blocos.
- **Se quem falha é o bloco `operacao`**, não há operação para devolver: a consulta falha com 503.
- **Na consulta por cliente ou conta**, uma lista de operações que falha em alguma custódia também gera 503. Uma lista incompleta pareceria completa ao consumidor.
- **Um bloco-lista nunca volta truncado.** Se a página 3 de parcelas falhar, o bloco `parcelas` inteiro fica indisponível; não se devolvem só as 60 primeiras parcelas.

Continuam encerrando a consulta inteira:
- o prazo da requisição esgotado (504);
- a cota da fonte primária esgotada (429).
