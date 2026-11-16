package com.persou.antifraud.contracts;

import java.time.Instant;
import java.util.List;

/**
 * Evento {@code FRAUD_DETECTED}, publicado em {@code fraud-alerts} (docs/event-contracts.md §3).
 * Só é publicado para análises com risco MEDIUM ou HIGH.
 */
public record FraudDetectedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        String transactionId,
        String customerId,
        String riskLevel,
        List<TriggeredRule> triggeredRules,
        Instant analyzedAt) {

    public static final String TYPE = "FRAUD_DETECTED";
    public static final int VERSION = 1;

    /** Regra acionada, com o motivo do acionamento (ADR 0001, decisão 7). */
    public record TriggeredRule(String ruleCode, String description, String reason) {
    }

    public static FraudDetectedEvent create(String eventId, String transactionId, String customerId,
                                            String riskLevel, List<TriggeredRule> triggeredRules,
                                            Instant analyzedAt) {
        return new FraudDetectedEvent(eventId, TYPE, VERSION, transactionId, customerId, riskLevel,
                List.copyOf(triggeredRules), analyzedAt);
    }
}
