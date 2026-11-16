package com.persou.antifraud.notification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;
import com.persou.antifraud.notification.application.port.out.NotificationRecord;
import com.persou.antifraud.notification.application.port.out.NotificationRepository;
import com.persou.antifraud.notification.application.port.out.NotificationStatus;
import com.persou.antifraud.notification.application.port.out.UnitOfWork;
import com.persou.antifraud.notification.domain.exception.InvalidAlertException;

class ProcessFraudAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T13:00:01Z");

    private InMemoryRepository repository;
    private FakeProvider provider;
    private ProcessFraudAlertService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        provider = new FakeProvider();
        service = new ProcessFraudAlertService(repository, provider, repository,
                Clock.fixed(NOW, ZoneOffset.UTC), "EMAIL", "ops@example.invalid");
    }

    private static FraudDetectedEvent alert(String eventId) {
        return FraudDetectedEvent.create(eventId, "t-1", "123", "MEDIUM", List.of(
                new FraudDetectedEvent.TriggeredRule("HIGH_TRANSACTION_AMOUNT", "d", "r")), NOW);
    }

    @Test
    void acceptedAlertIsRegisteredAsAcceptedByProvider() {
        var result = service.process(alert("a1"));

        assertThat(result.duplicate()).isFalse();
        NotificationRecord record = repository.records.get("a1");
        assertThat(record.status()).isEqualTo(NotificationStatus.ACCEPTED_BY_PROVIDER);
        assertThat(record.attempts()).isEqualTo(1);
        assertThat(record.providerReference()).isNotBlank();
        assertThat(provider.sent).hasSize(1);
    }

    @Test
    void redeliveryOfAcceptedAlertDoesNotSendAgain() {
        service.process(alert("a2"));

        var again = service.process(alert("a2"));

        assertThat(again.duplicate()).isTrue();
        assertThat(provider.sent).hasSize(1);
        assertThat(repository.records.get("a2").attempts()).isEqualTo(1);
    }

    @Test
    void providerFailureMarksFailedAndPropagatesForRetry() {
        provider.failuresRemaining = 1;

        assertThatThrownBy(() -> service.process(alert("a3")))
                .isInstanceOf(NotificationProviderException.class);
        NotificationRecord failed = repository.records.get("a3");
        assertThat(failed.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("NotificationProviderException");
        assertThat(failed.attempts()).isEqualTo(1);

        service.process(alert("a3"));

        NotificationRecord accepted = repository.records.get("a3");
        assertThat(accepted.status()).isEqualTo(NotificationStatus.ACCEPTED_BY_PROVIDER);
        assertThat(accepted.attempts()).isEqualTo(2);
        assertThat(accepted.lastError()).isNull();
    }

    @Test
    void invalidAlertIsRejectedAsNonRetryable() {
        assertThatThrownBy(() -> service.process(new FraudDetectedEvent("a4", "OTHER", 1, "t", "c", "HIGH",
                List.of(), NOW)))
                .isInstanceOf(InvalidAlertException.class);
        assertThatThrownBy(() -> service.process(alert("")))
                .isInstanceOf(InvalidAlertException.class);
        assertThat(provider.sent).isEmpty();
    }

    static final class InMemoryRepository implements NotificationRepository, UnitOfWork {

        final Map<String, NotificationRecord> records = new HashMap<>();

        @Override
        public Optional<NotificationRecord> findByEventId(String eventId) {
            return Optional.ofNullable(records.get(eventId));
        }

        @Override
        public void insertPending(NotificationRecord record) {
            records.put(record.eventId(), record);
        }

        @Override
        public void registerAttempt(String eventId) {
            update(eventId, r -> new NotificationRecord(r.eventId(), r.transactionId(), r.customerId(),
                    r.riskLevel(), r.channel(), r.recipient(), r.status(), r.providerReference(),
                    r.attempts() + 1, r.lastError(), NOW));
        }

        @Override
        public void markAccepted(String eventId, String providerReference) {
            update(eventId, r -> new NotificationRecord(r.eventId(), r.transactionId(), r.customerId(),
                    r.riskLevel(), r.channel(), r.recipient(), NotificationStatus.ACCEPTED_BY_PROVIDER,
                    providerReference, r.attempts(), null, NOW));
        }

        @Override
        public void markFailed(String eventId, String error) {
            update(eventId, r -> new NotificationRecord(r.eventId(), r.transactionId(), r.customerId(),
                    r.riskLevel(), r.channel(), r.recipient(), NotificationStatus.FAILED, null,
                    r.attempts(), error, NOW));
        }

        private void update(String eventId, java.util.function.UnaryOperator<NotificationRecord> change) {
            records.put(eventId, change.apply(records.get(eventId)));
        }

        @Override
        public <T> T execute(Supplier<T> work) {
            return work.get();
        }
    }

    static final class FakeProvider implements NotificationProvider {

        final List<NotificationMessage> sent = new ArrayList<>();
        int failuresRemaining;

        @Override
        public ProviderReceipt send(NotificationMessage message) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new NotificationProviderException("indisponível");
            }
            sent.add(message);
            return new ProviderReceipt("ref-" + sent.size());
        }
    }
}
