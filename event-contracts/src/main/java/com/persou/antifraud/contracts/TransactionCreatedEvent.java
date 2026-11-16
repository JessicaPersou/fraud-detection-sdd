package com.persou.antifraud.contracts;

import java.time.Instant;

/**
 * Evento {@code TRANSACTION_CREATED}, publicado em {@code transaction-events} (docs/event-contracts.md §2).
 *
 * <p>{@code occurredAt} é o instante em que o Transaction Service recebeu e registrou a transação.
 * É a origem da medição do SLA de 500 ms (docs/adr/0008-arquitetura-aws.md §2.8).
 */
public record TransactionCreatedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String transactionId,
        String customerId,
        long amountInCents,
        String currency,
        Instant occurredAt) {

    public static final String TYPE = "TRANSACTION_CREATED";
    public static final int VERSION = 1;

    public static TransactionCreatedEvent create(String eventId, String transactionId, String customerId,
                                                 long amountInCents, String currency, Instant occurredAt) {
        return new TransactionCreatedEvent(eventId, TYPE, VERSION, transactionId, customerId,
                amountInCents, currency, occurredAt);
    }
}
