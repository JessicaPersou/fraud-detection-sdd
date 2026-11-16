package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Resultado da análise (ADR 0001, decisão 7). Regras acionadas ficam como JSON. */
@Entity
@Table(name = "fraud_analysis")
public class FraudAnalysisJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "source_event_id", length = 36)
    private String sourceEventId;

    @Column(name = "alert_event_id", nullable = false, unique = true, length = 36)
    private String alertEventId;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 36)
    private String transactionId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "risk_level", nullable = false, length = 10)
    private String riskLevel;

    @Column(name = "triggered_rules", nullable = false, columnDefinition = "TEXT")
    private String triggeredRules;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(name = "alert_published", nullable = false)
    private boolean alertPublished;

    @Transient
    private boolean newEntity = true;

    protected FraudAnalysisJpaEntity() {
    }

    public FraudAnalysisJpaEntity(String sourceEventId, String alertEventId, String transactionId,
                                  String customerId, String riskLevel, String triggeredRules,
                                  Instant analyzedAt, boolean alertPublished) {
        this.sourceEventId = sourceEventId;
        this.alertEventId = alertEventId;
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.riskLevel = riskLevel;
        this.triggeredRules = triggeredRules;
        this.analyzedAt = analyzedAt;
        this.alertPublished = alertPublished;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getAlertEventId() {
        return alertEventId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getTriggeredRules() {
        return triggeredRules;
    }

    public Instant getAnalyzedAt() {
        return analyzedAt;
    }

    public boolean isAlertPublished() {
        return alertPublished;
    }

    public void markAlertPublished() {
        this.alertPublished = true;
    }

    @Override
    public String getId() {
        return sourceEventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
