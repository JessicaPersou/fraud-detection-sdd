package com.persou.antifraud.transaction.domain.exception;

/** A chave de idempotência já existe no banco. Barreira final contra concorrência (ADR 0004). */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException() {
        super("Idempotency-Key já registrada");
    }
}
