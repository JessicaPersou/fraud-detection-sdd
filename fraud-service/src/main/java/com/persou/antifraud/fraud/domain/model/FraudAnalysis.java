package com.persou.antifraud.fraud.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Resultado da análise de uma transação.
 *
 * @param sourceEventId  eventId do TransactionCreated analisado (chave da inbox)
 * @param alertEventId   eventId do FraudDetected, gerado uma vez e persistido (ADR 0001, decisão 2)
 */
public record FraudAnalysis(
        String sourceEventId,
        String alertEventId,
        String transactionId,
        String customerId,
        RiskLevel riskLevel,
        List<RuleFinding> findings,
        Instant analyzedAt) {

    /** Alerta é publicado para MEDIUM e HIGH (spec §5; cenário F-01). */
    public boolean alertRequired() {
        return riskLevel != RiskLevel.LOW;
    }
}
