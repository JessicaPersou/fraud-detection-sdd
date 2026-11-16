package com.persou.antifraud.transaction.domain.exception;

/** Requisição com a mesma chave ainda em andamento (ADR 0004). Resulta em 409. */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException() {
        super("Requisição com esta Idempotency-Key ainda está em andamento");
    }
}
