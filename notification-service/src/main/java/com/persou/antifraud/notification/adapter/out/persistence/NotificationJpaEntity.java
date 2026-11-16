package com.persou.antifraud.notification.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import com.persou.antifraud.notification.application.port.out.NotificationRecord;
import com.persou.antifraud.notification.application.port.out.NotificationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Persistable para que {@code saveAndFlush} use persist em registros novos. Com id atribuído,
 * o merge padrão sobrescreveria uma notificação existente.
 */
@Entity
@Table(name = "notification_record")
class NotificationJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "risk_level", nullable = false, length = 10)
    private String riskLevel;

    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    @Column(name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    protected NotificationJpaEntity() {
    }

    NotificationJpaEntity(NotificationRecord record) {
        this.eventId = record.eventId();
        this.transactionId = record.transactionId();
        this.customerId = record.customerId();
        this.riskLevel = record.riskLevel();
        this.channel = record.channel();
        this.recipient = record.recipient();
        this.status = record.status().name();
        this.providerReference = record.providerReference();
        this.attempts = record.attempts();
        this.lastError = record.lastError();
        this.updatedAt = record.updatedAt();
    }

    NotificationRecord toRecord() {
        return new NotificationRecord(eventId, transactionId, customerId, riskLevel, channel, recipient,
                NotificationStatus.valueOf(status), providerReference, attempts, lastError, updatedAt);
    }

    void registerAttempt(Instant at) {
        this.attempts++;
        this.updatedAt = at;
    }

    void markAccepted(String reference, Instant at) {
        this.status = NotificationStatus.ACCEPTED_BY_PROVIDER.name();
        this.providerReference = reference;
        this.lastError = null;
        this.updatedAt = at;
    }

    void markFailed(String error, Instant at) {
        this.status = NotificationStatus.FAILED.name();
        this.lastError = error;
        this.updatedAt = at;
    }

    @Override
    public String getId() {
        return eventId;
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
