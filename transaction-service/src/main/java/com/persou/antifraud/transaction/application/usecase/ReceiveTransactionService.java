package com.persou.antifraud.transaction.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.persou.antifraud.contracts.TransactionCreatedEvent;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionCommand;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionResult;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionResult.Outcome;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionUseCase;
import com.persou.antifraud.transaction.application.port.in.TransactionResponse;
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
import com.persou.antifraud.transaction.domain.model.CurrencyCatalog;
import com.persou.antifraud.transaction.domain.model.RequestFingerprint;
import com.persou.antifraud.transaction.domain.model.Transaction;

/**
 * Implementa o fluxo de {@code POST /transactions} (spec §6 e ADR 0004).
 *
 * <p>Sem anotações Spring: o bean é criado em {@code TransactionServiceConfig}.
 */
public class ReceiveTransactionService implements ReceiveTransactionUseCase {

    /** Retenção da chave de idempotência (ADR 0004). */
    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final IdempotencyRepository idempotency;
    private final TransactionRepository transactions;
    private final OutboxRepository outbox;
    private final UnitOfWork unitOfWork;
    private final PayloadSerializer serializer;
    private final Clock clock;
    private final Set<String> supportedCurrencies;
    private final Duration inProgressTimeout;

    public ReceiveTransactionService(IdempotencyRepository idempotency,
                                     TransactionRepository transactions,
                                     OutboxRepository outbox,
                                     UnitOfWork unitOfWork,
                                     PayloadSerializer serializer,
                                     Clock clock,
                                     Set<String> supportedCurrencies,
                                     Duration inProgressTimeout) {
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.outbox = outbox;
        this.unitOfWork = unitOfWork;
        this.serializer = serializer;
        this.clock = clock;
        this.supportedCurrencies = Set.copyOf(supportedCurrencies);
        this.inProgressTimeout = inProgressTimeout;
    }

    @Override
    public ReceiveTransactionResult receive(ReceiveTransactionCommand command) {
        validate(command);

        String key = command.idempotencyKey();
        String requestHash = RequestFingerprint.of(command.customerId(), command.amountInCents(), command.currency());
        Instant now = clock.instant();

        Optional<IdempotencyRecord> existing = idempotency.findByKey(key);
        if (existing.isPresent()) {
            Optional<ReceiveTransactionResult> handled = handleExisting(existing.get(), requestHash, now);
            if (handled.isPresent()) {
                return handled.get();
            }
        }

        reserveKey(key, requestHash, now);

        String transactionId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        Transaction transaction = new Transaction(transactionId, command.customerId(), command.amountInCents(),
                command.currency(), now);
        String responseBody = serializer.toJson(new TransactionResponse(transactionId, command.customerId(),
                command.amountInCents(), command.currency(), now));
        String eventPayload = serializer.toJson(TransactionCreatedEvent.create(eventId, transactionId,
                command.customerId(), command.amountInCents(), command.currency(), now));

        try {
            unitOfWork.execute(() -> {
                transactions.save(transaction);
                outbox.save(new OutboxMessage(eventId, transactionId, TransactionCreatedEvent.TYPE,
                        command.customerId(), eventPayload, now));
                idempotency.complete(key, transactionId, responseBody);
                return null;
            });
        } catch (RuntimeException e) {
            // Falha na segunda etapa: libera a chave para que o cliente possa repetir a requisição.
            releaseKey(key);
            throw e;
        }

        return new ReceiveTransactionResult(Outcome.CREATED, transactionId, responseBody);
    }

    /**
     * Trata uma chave já existente. Retorna vazio quando a chave expirou ou ficou abandonada
     * e a requisição deve prosseguir como nova.
     */
    private Optional<ReceiveTransactionResult> handleExisting(IdempotencyRecord record, String requestHash,
                                                              Instant now) {
        if (!record.expiresAt().isAfter(now)) {
            idempotency.delete(record.idempotencyKey());
            return Optional.empty();
        }
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        if (record.status() == IdempotencyStatus.COMPLETED) {
            return Optional.of(new ReceiveTransactionResult(Outcome.REPLAYED, record.transactionId(),
                    record.responseBody()));
        }
        if (record.createdAt().plus(inProgressTimeout).isAfter(now)) {
            throw new IdempotencyKeyInProgressException();
        }
        // IN_PROGRESS abandonado (ex.: crash entre as duas etapas): libera a chave (ADR 0004, riscos).
        idempotency.delete(record.idempotencyKey());
        return Optional.empty();
    }

    private void reserveKey(String key, String requestHash, Instant now) {
        IdempotencyRecord inProgress = new IdempotencyRecord(key, requestHash, IdempotencyStatus.IN_PROGRESS,
                null, null, now, now.plus(IDEMPOTENCY_RETENTION));
        try {
            unitOfWork.execute(() -> {
                idempotency.insertInProgress(inProgress);
                return null;
            });
        } catch (DuplicateIdempotencyKeyException e) {
            // Outra requisição com a mesma chave gravou primeiro: a barreira final do banco (ADR 0004).
            throw new IdempotencyKeyInProgressException();
        }
    }

    private void releaseKey(String key) {
        try {
            unitOfWork.execute(() -> {
                idempotency.delete(key);
                return null;
            });
        } catch (RuntimeException ignored) {
            // Melhor esforço: o registro IN_PROGRESS expira pelo timeout de abandono.
        }
    }

    private void validate(ReceiveTransactionCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new InvalidTransactionException("Idempotency-Key vazia");
        }
        if (command.customerId() == null || command.customerId().isBlank()) {
            throw new InvalidTransactionException("customerId obrigatório");
        }
        if (command.amountInCents() <= 0) {
            throw new InvalidTransactionException("amountInCents deve ser positivo");
        }
        if (command.currency() == null || !supportedCurrencies.contains(command.currency())) {
            throw new UnsupportedCurrencyException();
        }
        if (CurrencyCatalog.minorUnitDigits(command.currency()).isEmpty()) {
            throw new UnsupportedCurrencyException();
        }
    }
}
