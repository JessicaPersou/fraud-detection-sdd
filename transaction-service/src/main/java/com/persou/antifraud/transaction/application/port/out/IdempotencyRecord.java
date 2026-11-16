package com.persou.antifraud.transaction.application.port.out;

import java.time.Instant;

/** Registro da tabela de idempotência (ADR 0004). */
public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        String transactionId,
        String responseBody,
        Instant createdAt,
        Instant expiresAt) {
}
