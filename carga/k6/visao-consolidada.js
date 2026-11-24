// Rota visão consolidada (DynamoDB + cache Valkey): GET /v1/operacoes-credito
// Uso: k6 run -e RPS=500 -e DURACAO=3m carga/k6/visao-consolidada.js
//      k6 run -e MODO=rampa -e RPS=1500 -e DEGRAUS=6 -e DURACAO_DEGRAU=1m carga/k6/visao-consolidada.js
// Detalhes e como extrapolar para o Fargate: carga/README.md e docs/capacidade-ecs-fargate.md
import { cenarios, consultaOperacao, consultaCliente, consultaConta } from './lib/comum.js';

const CAMINHO = '/v1/operacoes-credito';

export const options = {
  scenarios: cenarios(300),
  thresholds: {
    // Alvos do requisito não funcional: P99 OPERACAO 80 ms, CLIENTE/CONTA 200 ms (prazo 150/400 ms)
    'duracao_200{tipo_consulta:OPERACAO}': [{ threshold: 'p(99)<80', abortOnFail: false }],
    'duracao_200{tipo_consulta:CLIENTE}': ['p(99)<200'],
    'duracao_200{tipo_consulta:CONTA}': ['p(99)<200'],
    // Nesta rota 429 não é esperado; qualquer status fora de 2xx/3xx (503 de bulkhead/rate limiter, 504) é erro
    http_req_failed: ['rate<0.01'],
    respostas_429: ['count==0'],
    // Sinal de saturação: o k6 não conseguiu manter a taxa (faltou VU porque a aplicação ficou lenta)
    dropped_iterations: ['count<1'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(99)', 'max'],
};

export function operacao() {
  consultaOperacao(CAMINHO);
}

export function cliente() {
  consultaCliente(CAMINHO);
}

export function conta() {
  consultaConta(CAMINHO);
}
