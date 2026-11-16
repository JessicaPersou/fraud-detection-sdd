package com.persou.antifraud.transaction.application.port.out;

import java.util.Optional;

public interface IdempotencyRepository {

    Optional<IdempotencyRecord> findByKey(String idempotencyKey);

    /**
     * Grava a chave como {@link IdempotencyStatus#IN_PROGRESS}.
     *
     * @throws com.persou.antifraud.transaction.domain.exception.DuplicateIdempotencyKeyException se a chave já existir
     */
    void insertInProgress(IdempotencyRecord record);

    void complete(String idempotencyKey, String transactionId, String responseBody);

    void delete(String idempotencyKey);
}
