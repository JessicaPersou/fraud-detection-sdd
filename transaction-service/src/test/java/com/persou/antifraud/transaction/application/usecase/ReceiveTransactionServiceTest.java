package com.persou.antifraud.transaction.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionCommand;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionResult;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionResult.Outcome;
import com.persou.antifraud.transaction.application.port.out.IdempotencyRecord;
import com.persou.antifraud.transaction.application.port.out.IdempotencyRepository;
import com.persou.antifraud.transaction.application.port.out.IdempotencyStatus;
import com.persou.antifraud.transaction.application.port.out.OutboxMessage;
import com.persou.antifraud.transaction.application.port.out.OutboxRepository;
import com.persou.antifraud.transaction.application.port.out.PayloadSerializer;
import com.persou.antifraud.transaction.application.port.out.TransactionRepository;
import com.persou.antifraud.transaction.application.port.out.UnitOfWork;
import com.persou.antifraud.transaction.domain.exception.DuplicateIdempotencyKeyException;
import com.persou.antifraud.transaction.domain.exception.IdempotencyKeyInProgressException;
import com.persou.antifraud.transaction.domain.exception.IdempotencyPayloadMismatchException;
import com.persou.antifraud.transaction.domain.exception.InvalidTransactionException;
import com.persou.antifraud.transaction.domain.exception.UnsupportedCurrencyException;
import com.persou.antifraud.transaction.domain.model.Transaction;

class ReceiveTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T13:00:00Z");
    private static final String KEY = "7f9c2e1a-5b3d-4c8e-9a10-2d6f4b8e1c57";
    private static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(5);

    private InMemoryStore store;
    private ReceiveTransactionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReceiveTransactionService(store, store, store, store, store, clock, Set.of("BRL"),
                IN_PROGRESS_TIMEOUT);
    }

    private static ReceiveTransactionCommand command(String key, String customerId, long amount, String currency) {
        return new ReceiveTransactionCommand(key, customerId, amount, currency);
    }

    @Test
    void newRequestStoresTransactionOutboxEventAndCompletedKeyTogether() {
        ReceiveTransactionResult result = service.receive(command(KEY, "123", 150_000, "BRL"));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(store.transactions).hasSize(1);
        Transaction transaction = store.transactions.get(0);
        assertThat(transaction.transactionId()).isEqualTo(result.transactionId());
        assertThat(transaction.customerId()).isEqualTo("123");

        assertThat(store.outbox).hasSize(1);
        OutboxMessage message = store.outbox.get(0);
        assertThat(message.eventType()).isEqualTo("TRANSACTION_CREATED");
        assertThat(message.partitionKey()).isEqualTo("123");
        assertThat(message.aggregateId()).isEqualTo(result.transactionId());

        IdempotencyRecord record = store.records.get(KEY);
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.transactionId()).isEqualTo(result.transactionId());
        assertThat(record.responseBody()).isEqualTo(result.responseBody());
    }

    @Test
    void sameKeyAndSamePayloadReplaysStoredResponseWithoutNewTransaction() {
        ReceiveTransactionResult first = service.receive(command(KEY, "123", 150_000, "BRL"));
        ReceiveTransactionResult second = service.receive(command(KEY, "123", 150_000, "BRL"));

        assertThat(second.outcome()).isEqualTo(Outcome.REPLAYED);
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.responseBody()).isEqualTo(first.responseBody());
        assertThat(store.transactions).hasSize(1);
        assertThat(store.outbox).hasSize(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedWithoutSideEffects() {
        service.receive(command(KEY, "123", 150_000, "BRL"));

        assertThatThrownBy(() -> service.receive(command(KEY, "123", 999, "BRL")))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThat(store.transactions).hasSize(1);
        assertThat(store.outbox).hasSize(1);
    }

    @Test
    void inProgressKeyWithinTimeoutIsRejectedAsConflict() {
        store.records.put(KEY, new IdempotencyRecord(KEY, hashOf("123", 150_000, "BRL"),
                IdempotencyStatus.IN_PROGRESS, null, null, NOW.minusSeconds(10), NOW.plusSeconds(3600)));

        assertThatThrownBy(() -> service.receive(command(KEY, "123", 150_000, "BRL")))
                .isInstanceOf(IdempotencyKeyInProgressException.class);
        assertThat(store.transactions).isEmpty();
    }

    @Test
    void abandonedInProgressKeyAfterTimeoutIsReleasedAndProcessed() {
        store.records.put(KEY, new IdempotencyRecord(KEY, hashOf("123", 150_000, "BRL"),
                IdempotencyStatus.IN_PROGRESS, null, null, NOW.minus(Duration.ofMinutes(10)),
                NOW.plusSeconds(3600)));

        ReceiveTransactionResult result = service.receive(command(KEY, "123", 150_000, "BRL"));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(store.transactions).hasSize(1);
    }

    @Test
    void expiredCompletedKeyIsTreatedAsNewRequest() {
        store.records.put(KEY, new IdempotencyRecord(KEY, hashOf("123", 150_000, "BRL"),
                IdempotencyStatus.COMPLETED, "old-id", "{\"old\":true}", NOW.minus(Duration.ofHours(25)),
                NOW.minus(Duration.ofHours(1))));

        ReceiveTransactionResult result = service.receive(command(KEY, "123", 150_000, "BRL"));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.transactionId()).isNotEqualTo("old-id");
    }

    @Test
    void unsupportedCurrencyIsRejectedBeforeAnyStorage() {
        assertThatThrownBy(() -> service.receive(command(KEY, "123", 150_000, "USD")))
                .isInstanceOf(UnsupportedCurrencyException.class);
        assertThat(store.records).isEmpty();
        assertThat(store.transactions).isEmpty();
    }

    @Test
    void nonPositiveAmountIsRejected() {
        assertThatThrownBy(() -> service.receive(command(KEY, "123", 0, "BRL")))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void failureInSecondStepReleasesKeySoClientCanRetry() {
        store.failOnOutbox = true;

        assertThatThrownBy(() -> service.receive(command(KEY, "123", 150_000, "BRL")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.records).doesNotContainKey(KEY);
        assertThat(store.transactions).isEmpty();

        store.failOnOutbox = false;
        ReceiveTransactionResult retry = service.receive(command(KEY, "123", 150_000, "BRL"));
        assertThat(retry.outcome()).isEqualTo(Outcome.CREATED);
    }

    private static String hashOf(String customerId, long amount, String currency) {
        return com.persou.antifraud.transaction.domain.model.RequestFingerprint.of(customerId, amount, currency);
    }

    /** Implementação em memória de todas as portas de saída. */
    static final class InMemoryStore implements IdempotencyRepository, TransactionRepository, OutboxRepository,
            UnitOfWork, PayloadSerializer {

        final Map<String, IdempotencyRecord> records = new HashMap<>();
        final List<Transaction> transactions = new ArrayList<>();
        final List<OutboxMessage> outbox = new ArrayList<>();
        boolean failOnOutbox;

        @Override
        public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
            return Optional.ofNullable(records.get(idempotencyKey));
        }

        @Override
        public void insertInProgress(IdempotencyRecord record) {
            if (records.containsKey(record.idempotencyKey())) {
                throw new DuplicateIdempotencyKeyException();
            }
            records.put(record.idempotencyKey(), record);
        }

        @Override
        public void complete(String idempotencyKey, String transactionId, String responseBody) {
            IdempotencyRecord current = records.get(idempotencyKey);
            records.put(idempotencyKey, new IdempotencyRecord(idempotencyKey, current.requestHash(),
                    IdempotencyStatus.COMPLETED, transactionId, responseBody, current.createdAt(),
                    current.expiresAt()));
        }

        @Override
        public void delete(String idempotencyKey) {
            records.remove(idempotencyKey);
        }

        @Override
        public void save(Transaction transaction) {
            transactions.add(transaction);
        }

        @Override
        public void save(OutboxMessage message) {
            if (failOnOutbox) {
                throw new IllegalStateException("falha simulada");
            }
            outbox.add(message);
        }

        /** Simula o rollback do banco: em caso de falha, restaura o estado anterior ao bloco. */
        @Override
        public <T> T execute(Supplier<T> work) {
            Map<String, IdempotencyRecord> recordsBefore = new HashMap<>(records);
            int transactionsBefore = transactions.size();
            int outboxBefore = outbox.size();
            try {
                return work.get();
            } catch (RuntimeException e) {
                records.clear();
                records.putAll(recordsBefore);
                transactions.subList(transactionsBefore, transactions.size()).clear();
                outbox.subList(outboxBefore, outbox.size()).clear();
                throw e;
            }
        }

        @Override
        public String toJson(Object payload) {
            return String.valueOf(payload);
        }
    }
}
