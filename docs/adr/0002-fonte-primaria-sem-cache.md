# A rota da fonte primária não usa cache

A API tem duas rotas com o mesmo contrato: `/v1/operacoes-credito` (visão consolidada, DynamoDB) e `/v1/operacoes-credito/fonte-primaria` (mainframe via gateway). O cache por bloco em Valkey vale só para a visão consolidada. A rota da fonte primária existe para quem não aceita a defasagem da visão consolidada; servir cache nela anularia esse propósito.

**Exceção (ADR-0012):** as duas rotas usam o cache de custódia, que guarda só em qual custódia a operação foi encontrada, nunca dados da operação. Uma custódia desatualizada é detectada na própria consulta, que redescobre a custódia certa, então não introduz defasagem.
