package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.stereotype.Repository;

import com.persou.antifraud.transaction.application.port.out.OutboxMessage;
import com.persou.antifraud.transaction.application.port.out.OutboxRepository;

@Repository
class JpaOutboxRepositoryAdapter implements OutboxRepository {

    private final SpringDataOutboxEventRepository repository;

    JpaOutboxRepositoryAdapter(SpringDataOutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxMessage message) {
        repository.save(new OutboxEventJpaEntity(message));
    }
}
