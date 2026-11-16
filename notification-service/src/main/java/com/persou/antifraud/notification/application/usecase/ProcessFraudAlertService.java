package com.persou.antifraud.notification.application.usecase;

import java.time.Clock;
import java.util.Optional;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.notification.application.port.in.ProcessFraudAlertUseCase;
import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationProvider.ProviderReceipt;
import com.persou.antifraud.notification.application.port.out.NotificationRecord;
import com.persou.antifraud.notification.application.port.out.NotificationRepository;
import com.persou.antifraud.notification.application.port.out.NotificationStatus;
import com.persou.antifraud.notification.application.port.out.UnitOfWork;
import com.persou.antifraud.notification.domain.exception.InvalidAlertException;

/**
 * Fluxo do Notification Service (spec §6).
 *
 * <p>Reentrega de alerta já aceito pelo provedor não gera novo envio (N-04). Falha do provedor
 * registra {@code FAILED} e propaga para o retry do consumidor. A entrega ao destinatário não é
 * afirmada: o registro para em {@code ACCEPTED_BY_PROVIDER} (N-03).
 *
 * <p>Sem anotações Spring: o bean é criado em {@code NotificationServiceConfig}.
 */
public class ProcessFraudAlertService implements ProcessFraudAlertUseCase {

    private final NotificationRepository repository;
    private final NotificationProvider provider;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final String channel;
    private final String recipient;

    public ProcessFraudAlertService(NotificationRepository repository,
                                    NotificationProvider provider,
                                    UnitOfWork unitOfWork,
                                    Clock clock,
                                    String channel,
                                    String recipient) {
        this.repository = repository;
        this.provider = provider;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.channel = channel;
        this.recipient = recipient;
    }

    @Override
    public ProcessingResult process(FraudDetectedEvent event) {
        validate(event);

        Optional<NotificationRecord> existing = repository.findByEventId(event.eventId());
        if (existing.isPresent() && existing.get().status() == NotificationStatus.ACCEPTED_BY_PROVIDER) {
            return new ProcessingResult(true, existing.get().providerReference());
        }

        unitOfWork.execute(() -> {
            if (existing.isEmpty()) {
                repository.insertPending(pending(event));
            }
            repository.registerAttempt(event.eventId());
            return null;
        });

        try {
            ProviderReceipt receipt = provider.send(message(event));
            repository.markAccepted(event.eventId(), receipt.reference());
            return new ProcessingResult(false, receipt.reference());
        } catch (RuntimeException e) {
            repository.markFailed(event.eventId(), sanitize(e));
            throw e;
        }
    }

    private NotificationRecord pending(FraudDetectedEvent event) {
        return new NotificationRecord(event.eventId(), event.transactionId(), event.customerId(),
                event.riskLevel(), channel, recipient, NotificationStatus.PENDING, null, 0, null,
                clock.instant());
    }

    private NotificationMessage message(FraudDetectedEvent event) {
        String subject = "Alerta antifraude: risco " + event.riskLevel();
        String body = "Transação " + event.transactionId() + " classificada com risco " + event.riskLevel()
                + " (" + event.triggeredRules().size() + " regra(s) acionada(s)).";
        return new NotificationMessage(event.eventId(), channel, recipient, subject, body);
    }

    /** Mensagem curta, sem detalhes internos. A exceção completa fica no log do consumidor. */
    private static String sanitize(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    private static void validate(FraudDetectedEvent event) {
        if (event == null) {
            throw new InvalidAlertException("Alerta nulo");
        }
        if (!FraudDetectedEvent.TYPE.equals(event.eventType())) {
            throw new InvalidAlertException("eventType inesperado");
        }
        if (event.eventVersion() != FraudDetectedEvent.VERSION) {
            throw new InvalidAlertException("eventVersion não suportada");
        }
        if (blank(event.eventId()) || blank(event.transactionId()) || blank(event.customerId())
                || blank(event.riskLevel())) {
            throw new InvalidAlertException("Campos obrigatórios ausentes");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
