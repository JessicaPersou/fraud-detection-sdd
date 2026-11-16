package com.persou.antifraud.transaction.domain.model;

import java.time.Instant;

/** Transação registrada pelo sistema. */
public record Transaction(
        String transactionId,
        String customerId,
        long amountInCents,
        String currency,
        Instant createdAt) {
}
