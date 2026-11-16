package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Instant;

import com.persou.antifraud.transaction.application.port.out.OutboxMessage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Registro da tabela outbox (ADR 0003). {@code published_at} nulo indica pendente. */
@Entity
@Table(name = "outbox_event", indexes = @Index(name = "idx_outbox_published", columnList = "published_at, id"))
public class OutboxEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "trace_parent", length = 80)
    private String traceParent;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEventJpaEntity() {
    }

    OutboxEventJpaEntity(OutboxMessage message, String traceParent) {
        this.traceParent = traceParent;
        this.eventId = message.eventId();
        this.aggregateId = message.aggregateId();
        this.eventType = message.eventType();
        this.partitionKey = message.partitionKey();
        this.payload = message.payload();
        this.createdAt = message.createdAt();
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public String getPayload() {
        return payload;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
