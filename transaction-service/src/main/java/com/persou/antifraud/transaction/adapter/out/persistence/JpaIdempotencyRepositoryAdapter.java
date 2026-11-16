package com.persou.antifraud.transaction.adapter.out.persistence;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.persou.antifraud.transaction.application.port.out.IdempotencyRecord;
import com.persou.antifraud.transaction.application.port.out.IdempotencyRepository;
import com.persou.antifraud.transaction.domain.exception.DuplicateIdempotencyKeyException;

@Repository
class JpaIdempotencyRepositoryAdapter implements IdempotencyRepository {

    private final SpringDataIdempotencyKeyRepository repository;

    JpaIdempotencyRepositoryAdapter(SpringDataIdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        return repository.findById(idempotencyKey).map(IdempotencyKeyJpaEntity::toRecord);
    }

    @Override
    public void insertInProgress(IdempotencyRecord record) {
        try {
            repository.saveAndFlush(new IdempotencyKeyJpaEntity(record));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIdempotencyKeyException();
        }
    }

    @Override
    public void complete(String idempotencyKey, String transactionId, String responseBody) {
        IdempotencyKeyJpaEntity entity = repository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Chave de idempotência ausente ao concluir"));
        entity.complete(transactionId, responseBody);
    }

    @Override
    public void delete(String idempotencyKey) {
        repository.deleteById(idempotencyKey);
    }
}
