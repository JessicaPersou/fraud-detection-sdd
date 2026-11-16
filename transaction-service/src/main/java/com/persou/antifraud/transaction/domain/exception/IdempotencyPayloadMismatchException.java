package com.persou.antifraud.transaction.domain.exception;

/** Mesma {@code Idempotency-Key} com payload diferente (ADR 0004). Resulta em 422. */
public class IdempotencyPayloadMismatchException extends RuntimeException {

    public IdempotencyPayloadMismatchException() {
        super("Idempotency-Key reutilizada com payload diferente");
    }
}
