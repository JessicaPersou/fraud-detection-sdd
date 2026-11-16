package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Histórico de transações para a janela da BR-01. Índice por cliente e instante. */
@Entity
@Table(name = "transaction_history",
        indexes = @Index(name = "idx_history_customer_time", columnList = "customer_id, occurred_at"))
public class TransactionHistoryJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount_in_cents", nullable = false)
    private long amountInCents;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean newEntity = true;

    protected TransactionHistoryJpaEntity() {
    }

    public TransactionHistoryJpaEntity(String transactionId, String customerId, long amountInCents,
                                       Instant occurredAt) {
        this.transactionId = transactionId;
        this.customerId = customerId;
        this.amountInCents = amountInCents;
        this.occurredAt = occurredAt;
    }

    @Override
    public String getId() {
        return transactionId;
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
