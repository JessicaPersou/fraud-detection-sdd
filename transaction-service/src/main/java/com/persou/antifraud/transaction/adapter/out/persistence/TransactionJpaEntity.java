package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Instant;

import com.persou.antifraud.transaction.domain.model.Transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "transaction_record", indexes = @Index(name = "idx_transaction_customer", columnList = "customer_id"))
class TransactionJpaEntity {

    @Id
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount_in_cents", nullable = false)
    private long amountInCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransactionJpaEntity() {
    }

    TransactionJpaEntity(Transaction transaction) {
        this.transactionId = transaction.transactionId();
        this.customerId = transaction.customerId();
        this.amountInCents = transaction.amountInCents();
        this.currency = transaction.currency();
        this.createdAt = transaction.createdAt();
    }
}
