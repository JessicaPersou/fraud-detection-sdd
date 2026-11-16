package com.persou.antifraud.notification.adapter.out.provider;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.persou.antifraud.notification.application.port.out.NotificationMessage;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationProviderException;

/**
 * Provedor simulado (fake provider), usado na v1 (spec §5; implementation-plan, Fase 4).
 *
 * <p>Aceita as mensagens e registra apenas o identificador do evento: o destinatário não vai para o log.
 * {@link #failNext(int)} permite simular indisponibilidade do provedor nos testes.
 */
@Component
@ConditionalOnProperty(name = "notification.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SimulatedNotificationProvider.class);

    private final AtomicInteger failuresRemaining = new AtomicInteger();

    @Override
    public ProviderReceipt send(NotificationMessage message) {
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new NotificationProviderException("Provedor indisponível (simulado)");
        }
        String reference = "sim-" + UUID.randomUUID();
        log.info("Notificação aceita pelo provedor simulado: eventId={} reference={}",
                message.eventId(), reference);
        return new ProviderReceipt(reference);
    }

    /** Faz as próximas {@code count} chamadas falharem. Uso restrito a testes. */
    public void failNext(int count) {
        failuresRemaining.set(count);
    }
}
