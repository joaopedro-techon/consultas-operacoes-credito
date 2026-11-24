package br.com.consultas.operacoescredito.adapters.in.web;

/** A rota pedida não está ativa nesta instância ({@code consultas.rotas-ativas}): 404. */
class RotaInativaException extends RuntimeException {

    RotaInativaException(String rota) {
        super("Rota " + rota + " não está ativa nesta instância");
    }
}
