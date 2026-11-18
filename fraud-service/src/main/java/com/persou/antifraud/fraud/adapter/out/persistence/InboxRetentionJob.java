package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.fraud.config.FraudProperties;

/** Limpeza da inbox além da retenção de 8 dias (R5, ADR 0005). */
@Component
class InboxRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(InboxRetentionJob.class);

    private final SpringDataInboxRepository inbox;
    private final FraudProperties.Retention retention;
    private final Clock clock;

    InboxRetentionJob(SpringDataInboxRepository inbox, FraudProperties properties, Clock clock) {
        this.inbox = inbox;
        this.retention = properties.retention();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${fraud.retention.cleanup-interval-ms}")
    @Transactional
    public void cleanup() {
        int removed = inbox.deleteProcessedBefore(clock.instant().minus(retention.inbox()));
        if (removed > 0) {
            log.info("Limpeza da inbox: {} evento(s) removido(s)", removed);
        }
    }
}
