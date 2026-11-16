package com.persou.antifraud.fraud.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.contracts.TransactionCreatedEvent;
import com.persou.antifraud.fraud.application.port.in.AnalysisResult;
import com.persou.antifraud.fraud.application.port.in.AnalyzeTransactionUseCase;
import com.persou.antifraud.fraud.application.port.out.FraudAlertPublisher;
import com.persou.antifraud.fraud.application.port.out.FraudAnalysisRepository;
import com.persou.antifraud.fraud.application.port.out.InboxRepository;
import com.persou.antifraud.fraud.application.port.out.StoredAnalysis;
import com.persou.antifraud.fraud.application.port.out.TransactionFrequencyPort;
import com.persou.antifraud.fraud.application.port.out.TransactionFrequencyPort.TransactionHistoryEntry;
import com.persou.antifraud.fraud.application.port.out.UnitOfWork;
import com.persou.antifraud.fraud.domain.exception.InvalidEventException;
import com.persou.antifraud.fraud.domain.model.FraudAnalysis;
import com.persou.antifraud.fraud.domain.model.RiskLevel;
import com.persou.antifraud.fraud.domain.model.RuleFinding;
import com.persou.antifraud.fraud.domain.service.FraudRuleEvaluator;
import com.persou.antifraud.fraud.domain.service.RiskClassifier;

/**
 * Fluxo de análise (spec §7).
 *
 * <ol>
 *   <li>Valida o contrato.</li>
 *   <li>Se o eventId já está na inbox, pula a análise e apenas garante que o alerta foi publicado.</li>
 *   <li>Em uma transação: inbox, histórico, regras, classificação e análise.</li>
 *   <li>Publica o alerta quando aplicável. Falha propaga, e a etapa é retentada com o mesmo eventId.</li>
 * </ol>
 *
 * <p>Sem anotações Spring: o bean é criado em {@code FraudServiceConfig}.
 */
public class AnalyzeTransactionService implements AnalyzeTransactionUseCase {

    private final FraudRuleEvaluator evaluator;
    private final Duration window;
    private final InboxRepository inbox;
    private final FraudAnalysisRepository analyses;
    private final TransactionFrequencyPort frequency;
    private final FraudAlertPublisher alertPublisher;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public AnalyzeTransactionService(FraudRuleEvaluator evaluator,
                                     Duration window,
                                     InboxRepository inbox,
                                     FraudAnalysisRepository analyses,
                                     TransactionFrequencyPort frequency,
                                     FraudAlertPublisher alertPublisher,
                                     UnitOfWork unitOfWork,
                                     Clock clock) {
        this.evaluator = evaluator;
        this.window = window;
        this.inbox = inbox;
        this.analyses = analyses;
        this.frequency = frequency;
        this.alertPublisher = alertPublisher;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    @Override
    public AnalysisResult analyze(TransactionCreatedEvent event) {
        validate(event);

        if (inbox.exists(event.eventId())) {
            return resumeAlert(event.eventId());
        }

        FraudAnalysis analysis = unitOfWork.execute(() -> analyzeAndStore(event));

        boolean published = false;
        if (analysis.alertRequired()) {
            publishAlert(analysis);
            published = true;
        }
        return new AnalysisResult(analysis.riskLevel(), false, published);
    }

    private FraudAnalysis analyzeAndStore(TransactionCreatedEvent event) {
        var now = clock.instant();
        inbox.insert(event.eventId(), now);

        frequency.record(new TransactionHistoryEntry(event.transactionId(), event.customerId(),
                event.amountInCents(), event.occurredAt()));
        long transactionsInWindow = frequency.countInWindow(event.customerId(), event.occurredAt(), window);

        List<RuleFinding> findings = evaluator.evaluate(event.amountInCents(), transactionsInWindow);
        FraudAnalysis analysis = new FraudAnalysis(event.eventId(), UUID.randomUUID().toString(),
                event.transactionId(), event.customerId(), RiskClassifier.classify(findings), findings, now);
        analyses.save(analysis);
        return analysis;
    }

    /** Reentrega de evento já processado: só falta publicar o alerta, se ainda não foi publicado. */
    private AnalysisResult resumeAlert(String sourceEventId) {
        StoredAnalysis stored = analyses.findBySourceEventId(sourceEventId)
                .orElseThrow(() -> new IllegalStateException("Inbox sem análise correspondente"));
        FraudAnalysis analysis = stored.analysis();

        if (!analysis.alertRequired()) {
            return new AnalysisResult(analysis.riskLevel(), true, false);
        }
        if (!stored.alertPublished()) {
            publishAlert(analysis);
        }
        return new AnalysisResult(analysis.riskLevel(), true, true);
    }

    private void publishAlert(FraudAnalysis analysis) {
        List<FraudDetectedEvent.TriggeredRule> rules = analysis.findings().stream()
                .map(f -> new FraudDetectedEvent.TriggeredRule(f.ruleCode(), f.description(), f.reason()))
                .toList();
        alertPublisher.publish(FraudDetectedEvent.create(analysis.alertEventId(), analysis.transactionId(),
                analysis.customerId(), analysis.riskLevel().name(), rules, clock.instant()));
        analyses.markAlertPublished(analysis.sourceEventId());
    }

    private static void validate(TransactionCreatedEvent event) {
        if (event == null) {
            throw new InvalidEventException("Evento nulo");
        }
        if (!TransactionCreatedEvent.TYPE.equals(event.eventType())) {
            throw new InvalidEventException("eventType inesperado");
        }
        if (event.eventVersion() != TransactionCreatedEvent.VERSION) {
            throw new InvalidEventException("eventVersion não suportada");
        }
        if (blank(event.eventId()) || blank(event.transactionId()) || blank(event.customerId())) {
            throw new InvalidEventException("Identificadores obrigatórios ausentes");
        }
        if (event.amountInCents() <= 0 || event.occurredAt() == null) {
            throw new InvalidEventException("Valor ou instante inválidos");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
