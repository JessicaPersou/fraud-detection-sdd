package com.persou.antifraud.transaction.adapter.out.kafka;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.transaction.adapter.out.persistence.OutboxEventJpaEntity;
import com.persou.antifraud.transaction.adapter.out.persistence.SpringDataOutboxEventRepository;
import com.persou.antifraud.transaction.config.TransactionProperties;

/**
 * Publicador do outbox por polling (ADR 0003).
 *
 * <p>Em falha, o registro permanece pendente e é reenviado com o mesmo {@code eventId}.
 * A lista é processada em ordem e interrompida no primeiro erro, para preservar a ordem por cliente.
 * O marcador de publicado só é gravado após o ack do Kafka. Uma queda entre o ack e o commit
 * gera reenvio: é o caminho at-least-once, e os consumidores deduplicam por {@code eventId}.
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final SpringDataOutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionProperties.Outbox settings;
    private final Clock clock;

    OutboxPublisher(SpringDataOutboxEventRepository repository,
                    KafkaTemplate<String, String> kafka,
                    TransactionProperties properties,
                    Clock clock) {
        this.repository = repository;
        this.kafka = kafka;
        this.settings = properties.outbox();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval-ms}")
    @Transactional
    public int publishPending() {
        List<OutboxEventJpaEntity> batch = repository.lockPending(settings.batchSize());
        int published = 0;
        for (OutboxEventJpaEntity event : batch) {
            try {
                kafka.send(settings.topic(), event.getPartitionKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.markPublished(clock.instant());
                published++;
            } catch (Exception e) {
                log.warn("Falha ao publicar evento {}; será reenviado com o mesmo eventId", event.getEventId(), e);
                break;
            }
        }
        return published;
    }
}
