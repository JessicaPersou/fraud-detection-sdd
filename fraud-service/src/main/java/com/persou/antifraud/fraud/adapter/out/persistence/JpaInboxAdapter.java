package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.persou.antifraud.fraud.application.port.out.InboxRepository;
import com.persou.antifraud.fraud.domain.exception.DuplicateEventException;

@Repository
class JpaInboxAdapter implements InboxRepository {

    private final SpringDataInboxRepository repository;

    JpaInboxAdapter(SpringDataInboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean exists(String eventId) {
        return repository.existsById(eventId);
    }

    @Override
    public void insert(String eventId, Instant processedAt) {
        try {
            repository.saveAndFlush(new InboxEventJpaEntity(eventId, processedAt));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateEventException();
        }
    }
}
