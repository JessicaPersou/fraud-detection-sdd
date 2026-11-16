package com.persou.antifraud.fraud.application.usecase;

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
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.contracts.TransactionCreatedEvent;
import com.persou.antifraud.fraud.application.port.in.AnalysisResult;
import com.persou.antifraud.fraud.application.port.out.FraudAlertPublisher;
import com.persou.antifraud.fraud.application.port.out.FraudAnalysisRepository;
import com.persou.antifraud.fraud.application.port.out.InboxRepository;
import com.persou.antifraud.fraud.application.port.out.StoredAnalysis;
import com.persou.antifraud.fraud.application.port.out.TransactionFrequencyPort;
import com.persou.antifraud.fraud.application.port.out.UnitOfWork;
import com.persou.antifraud.fraud.domain.exception.DuplicateEventException;
import com.persou.antifraud.fraud.domain.exception.InvalidEventException;
import com.persou.antifraud.fraud.domain.model.FraudAnalysis;
import com.persou.antifraud.fraud.domain.model.RiskLevel;
import com.persou.antifraud.fraud.domain.service.FraudRuleEvaluator;

class AnalyzeTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T13:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private InMemoryFraudStore store;
    private RecordingPublisher publisher;
    private AnalyzeTransactionService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryFraudStore();
        publisher = new RecordingPublisher();
        service = new AnalyzeTransactionService(new FraudRuleEvaluator(100_000, 5, 60), WINDOW,
                store, store, store, publisher, store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TransactionCreatedEvent event(String eventId, String transactionId, long amount) {
        return TransactionCreatedEvent.create(eventId, transactionId, "123", amount, "BRL", NOW);
    }

    @Test
    void lowRiskTransactionIsStoredWithoutAlert() {
        AnalysisResult result = service.analyze(event("e1", "t1", 50_000));

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.alertPublished()).isFalse();
        assertThat(publisher.published).isEmpty();
        assertThat(store.analyses).containsKey("e1");
    }

    @Test
    void amountAboveThresholdPublishesMediumAlertWithRuleExplanation() {
        AnalysisResult result = service.analyze(event("e2", "t2", 150_000));

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.alertPublished()).isTrue();
        assertThat(publisher.published).hasSize(1);
        FraudDetectedEvent alert = publisher.published.get(0);
        assertThat(alert.riskLevel()).isEqualTo("MEDIUM");
        assertThat(alert.transactionId()).isEqualTo("t2");
        assertThat(alert.triggeredRules()).extracting(FraudDetectedEvent.TriggeredRule::ruleCode)
                .containsExactly("HIGH_TRANSACTION_AMOUNT");
        assertThat(store.analyses.get("e2").alertPublished()).isTrue();
    }

    @Test
    void frequencyIncludesCurrentTransactionInSlidingWindow() {
        for (int i = 1; i <= 5; i++) {
            assertThat(service.analyze(event("f" + i, "tf" + i, 1_000)).riskLevel()).isEqualTo(RiskLevel.LOW);
        }

        AnalysisResult sixth = service.analyze(event("f6", "tf6", 1_000));

        assertThat(sixth.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(publisher.published.get(0).triggeredRules())
                .extracting(FraudDetectedEvent.TriggeredRule::ruleCode).containsExactly("TRANSACTION_FREQUENCY");
    }

    @Test
    void duplicateEventDoesNotRepeatAnalysisOrAlert() {
        service.analyze(event("e3", "t3", 150_000));

        AnalysisResult duplicate = service.analyze(event("e3", "t3", 150_000));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(publisher.published).hasSize(1);
        assertThat(store.transactionHistoryCount()).isEqualTo(1);
    }

    @Test
    void alertPublishFailureIsRetriedWithSameAlertEventIdWithoutNewAnalysis() {
        publisher.failNext = true;
        assertThatThrownBy(() -> service.analyze(event("e4", "t4", 150_000)))
                .isInstanceOf(IllegalStateException.class);

        String alertEventId = store.analyses.get("e4").analysis().alertEventId();
        assertThat(store.analyses.get("e4").alertPublished()).isFalse();

        AnalysisResult retry = service.analyze(event("e4", "t4", 150_000));

        assertThat(retry.alertPublished()).isTrue();
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).eventId()).isEqualTo(alertEventId);
        assertThat(store.transactionHistoryCount()).isEqualTo(1);
    }

    @Test
    void invalidEventIsRejectedAsNonRetryable() {
        assertThatThrownBy(() -> service.analyze(new TransactionCreatedEvent("e5", "OTHER", 1, "t5", "123",
                150_000, "BRL", NOW)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.analyze(event("", "t6", 150_000)))
                .isInstanceOf(InvalidEventException.class);
        assertThat(store.analyses).isEmpty();
    }

    /** Implementação em memória das portas de saída. */
    static final class InMemoryFraudStore implements InboxRepository, FraudAnalysisRepository,
            TransactionFrequencyPort, UnitOfWork {

        final Map<String, StoredAnalysis> analyses = new HashMap<>();
        final Map<String, Instant> inbox = new HashMap<>();
        final List<TransactionHistoryEntry> history = new ArrayList<>();

        @Override
        public boolean exists(String eventId) {
            return inbox.containsKey(eventId);
        }

        @Override
        public void insert(String eventId, Instant processedAt) {
            if (inbox.containsKey(eventId)) {
                throw new DuplicateEventException();
            }
            inbox.put(eventId, processedAt);
        }

        @Override
        public void save(FraudAnalysis analysis) {
            analyses.put(analysis.sourceEventId(), new StoredAnalysis(analysis, false));
        }

        @Override
        public Optional<StoredAnalysis> findBySourceEventId(String sourceEventId) {
            return Optional.ofNullable(analyses.get(sourceEventId));
        }

        @Override
        public void markAlertPublished(String sourceEventId) {
            StoredAnalysis current = analyses.get(sourceEventId);
            analyses.put(sourceEventId, new StoredAnalysis(current.analysis(), true));
        }

        @Override
        public void record(TransactionHistoryEntry entry) {
            history.add(entry);
        }

        @Override
        public long countInWindow(String customerId, Instant end, Duration window) {
            Instant from = end.minus(window);
            return history.stream()
                    .filter(h -> h.customerId().equals(customerId))
                    .filter(h -> !h.occurredAt().isBefore(from) && !h.occurredAt().isAfter(end))
                    .count();
        }

        int transactionHistoryCount() {
            return history.size();
        }

        @Override
        public <T> T execute(Supplier<T> work) {
            return work.get();
        }
    }

    static final class RecordingPublisher implements FraudAlertPublisher {

        final List<FraudDetectedEvent> published = new ArrayList<>();
        boolean failNext;

        @Override
        public void publish(FraudDetectedEvent event) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("falha simulada no Kafka");
            }
            published.add(event);
        }
    }
}
