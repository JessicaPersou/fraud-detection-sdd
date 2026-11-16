package com.persou.antifraud.transaction.application.port.in;

import java.time.Instant;

/** Corpo da resposta de {@code POST /transactions}. */
public record TransactionResponse(
        String transactionId,
        String customerId,
        long amountInCents,
        String currency,
        Instant createdAt) {
}
