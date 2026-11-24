// Rota fonte primária (gateway mainframe; WireMock no ambiente local): GET /v1/operacoes-credito/fonte-primaria
// Uso: k6 run -e RPS=30 -e DURACAO=3m carga/k6/fonte-primaria.js
//      k6 run -e MODO=rampa -e RPS=120 carga/k6/fonte-primaria.js
// O 429 (cota local da task esgotada, ADR-0015) é contado à parte: não entra em http_req_failed nem na latência.
// Sem latência no WireMock o teste não diz nada sobre concorrência: configure o atraso (carga/README.md).
import http from 'k6/http';
import { cenarios, consultaOperacao, consultaCliente, consultaConta } from './lib/comum.js';

const CAMINHO = '/v1/operacoes-credito/fonte-primaria';

// 429 é recusa esperada por cota, não falha da aplicação
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 429));

export const options = {
  scenarios: cenarios(30),
  thresholds: {
    // Prazos da rota: OPERACAO 2,5 s, CLIENTE/CONTA 6 s; os alvos abaixo deixam folga para o retry
    'duracao_200{tipo_consulta:OPERACAO}': ['p(99)<1500'],
    'duracao_200{tipo_consulta:CLIENTE}': ['p(99)<4000'],
    'duracao_200{tipo_consulta:CONTA}': ['p(99)<4000'],
    // 5xx/504 (fora o 429) abaixo de 1%
    http_req_failed: ['rate<0.01'],
    // Abaixo da cota o 429 deve ser raro; acima dela, é o comportamento esperado (ajuste MAX_429)
    taxa_429: [`rate<${__ENV.MAX_429 || 0.01}`],
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
