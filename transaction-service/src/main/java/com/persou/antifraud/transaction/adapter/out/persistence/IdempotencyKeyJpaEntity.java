package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import com.persou.antifraud.transaction.application.port.out.IdempotencyRecord;
import com.persou.antifraud.transaction.application.port.out.IdempotencyStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * Implementa {@link Persistable} para que {@code saveAndFlush} use {@code persist} em registros novos.
 * Com um id atribuído, o {@code merge} padrão sobrescreveria silenciosamente uma chave já existente.
 */
@Entity
@Table(name = "idempotency_key", indexes = @Index(name = "idx_idempotency_expires", columnList = "expires_at"))
class IdempotencyKeyJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean newEntity = true;

    protected IdempotencyKeyJpaEntity() {
    }

    IdempotencyKeyJpaEntity(IdempotencyRecord record) {
        this.idempotencyKey = record.idempotencyKey();
        this.requestHash = record.requestHash();
        this.status = record.status().name();
        this.transactionId = record.transactionId();
        this.responseBody = record.responseBody();
        this.createdAt = record.createdAt();
        this.expiresAt = record.expiresAt();
    }

    IdempotencyRecord toRecord() {
        return new IdempotencyRecord(idempotencyKey, requestHash, IdempotencyStatus.valueOf(status),
                transactionId, responseBody, createdAt, expiresAt);
    }

    void complete(String transactionId, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED.name();
        this.transactionId = transactionId;
        this.responseBody = responseBody;
    }

    @Override
    public String getId() {
        return idempotencyKey;
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
