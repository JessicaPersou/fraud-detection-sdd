package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.stereotype.Repository;

import com.persou.antifraud.transaction.application.port.out.OutboxMessage;
import com.persou.antifraud.transaction.application.port.out.OutboxRepository;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

@Repository
class JpaOutboxRepositoryAdapter implements OutboxRepository {

    private final SpringDataOutboxEventRepository repository;
    private final Tracer tracer;

    JpaOutboxRepositoryAdapter(SpringDataOutboxEventRepository repository, Tracer tracer) {
        this.repository = repository;
        this.tracer = tracer;
    }

    /** Grava o evento com o contexto de trace atual (W3C traceparent) para continuar o trace na publicação. */
    @Override
    public void save(OutboxMessage message) {
        repository.save(new OutboxEventJpaEntity(message, currentTraceParent()));
    }

    private String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        return "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
    }
}
