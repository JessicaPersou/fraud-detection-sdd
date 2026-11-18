package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.transaction.config.TransactionProperties;

/**
 * Limpeza de registros expirados (R5, ADR 0004 e ADR 0003).
 *
 * <ul>
 *   <li>Chaves de idempotência além das 24 h de retenção.</li>
 *   <li>Registros do outbox já publicados há mais de {@code transaction.retention.published-outbox}.</li>
 * </ul>
 * Registros pendentes do outbox nunca são removidos.
 */
@Component
class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final SpringDataIdempotencyKeyRepository idempotencyKeys;
    private final SpringDataOutboxEventRepository outbox;
    private final TransactionProperties.Retention retention;
    private final Clock clock;

    RetentionCleanupJob(SpringDataIdempotencyKeyRepository idempotencyKeys,
                        SpringDataOutboxEventRepository outbox,
                        TransactionProperties properties,
                        Clock clock) {
        this.idempotencyKeys = idempotencyKeys;
        this.outbox = outbox;
        this.retention = properties.retention();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${transaction.retention.cleanup-interval-ms}")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        int keys = idempotencyKeys.deleteExpired(now);
        int events = outbox.deletePublishedBefore(now.minus(retention.publishedOutbox()));
        if (keys > 0 || events > 0) {
            log.info("Limpeza de retenção: {} chave(s) de idempotência e {} evento(s) do outbox removidos",
                    keys, events);
        }
    }
}
