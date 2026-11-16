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

/** Inbox de consumo (ADR 0005, seção 4). Só é gravada na mesma transação da análise. */
@Entity
@Table(name = "inbox_event")
public class InboxEventJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    protected InboxEventJpaEntity() {
    }

    public InboxEventJpaEntity(String eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
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
