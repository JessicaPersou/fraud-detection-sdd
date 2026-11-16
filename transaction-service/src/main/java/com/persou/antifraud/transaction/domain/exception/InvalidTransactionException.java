package com.persou.antifraud.transaction.domain.exception;

/** Dados de entrada inválidos. A mensagem é interna; a resposta HTTP é genérica (spec §7). */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
