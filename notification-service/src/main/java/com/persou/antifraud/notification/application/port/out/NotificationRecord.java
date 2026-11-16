package com.persou.antifraud.notification.application.port.out;

import java.time.Instant;

/**
 * Registro de notificação. O destinatário fica registrado para auditoria, mas nunca é gravado em log.
 */
public record NotificationRecord(
        String eventId,
        String transactionId,
        String customerId,
        String riskLevel,
        String channel,
        String recipient,
        NotificationStatus status,
        String providerReference,
        int attempts,
        String lastError,
        Instant updatedAt) {
}
