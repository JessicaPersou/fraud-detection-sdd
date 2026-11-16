package com.persou.antifraud.transaction.application.port.in;

public record ReceiveTransactionCommand(
        String idempotencyKey,
        String customerId,
        long amountInCents,
        String currency) {
}
