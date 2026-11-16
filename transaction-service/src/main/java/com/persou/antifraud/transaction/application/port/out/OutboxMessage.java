package com.persou.antifraud.transaction.application.port.out;

import java.time.Instant;

/**
 * Mensagem a ser gravada na tabela outbox, na mesma transação de banco do agregado (ADR 0001, decisão 5).
 *
 * @param payload JSON do evento, já serializado
 */
public record OutboxMessage(
        String eventId,
        String aggregateId,
        String eventType,
        String partitionKey,
        String payload,
        Instant createdAt) {
}
