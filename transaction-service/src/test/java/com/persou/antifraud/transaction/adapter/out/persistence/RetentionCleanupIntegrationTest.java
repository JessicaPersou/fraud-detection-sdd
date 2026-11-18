package com.persou.antifraud.transaction.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.testsupport.SharedInfrastructure;

/** Limpeza de retenção (R5): remove só o que expirou ou já foi publicado há mais do que a retenção. */
@SpringBootTest
class RetentionCleanupIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionCleanupJob cleanup;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM outbox_event");
    }

    @Test
    void removesExpiredKeysAndOldPublishedEventsOnly() {
        Instant now = Instant.now();
        insertKey("expired", now.minus(Duration.ofHours(1)));
        insertKey("active", now.plus(Duration.ofHours(23)));
        insertEvent("published-old", now.minus(Duration.ofDays(8)));
        insertEvent("published-recent", now.minus(Duration.ofDays(1)));
        insertEvent("pending-old", null, now.minus(Duration.ofDays(30)));

        cleanup.cleanup();

        assertThat(jdbc.queryForList("SELECT idempotency_key FROM idempotency_key", String.class))
                .containsExactly("active");
        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_event ORDER BY aggregate_id", String.class))
                .containsExactly("pending-old", "published-recent");
    }

    private void insertKey(String key, Instant expiresAt) {
        jdbc.update("INSERT INTO idempotency_key (idempotency_key, request_hash, status, created_at, expires_at) "
                        + "VALUES (?, ?, 'COMPLETED', ?, ?)",
                key, "a".repeat(64), utc(expiresAt.minus(Duration.ofHours(24))), utc(expiresAt));
    }

    private void insertEvent(String aggregateId, Instant publishedAt) {
        insertEvent(aggregateId, publishedAt, Instant.now());
    }

    private void insertEvent(String aggregateId, Instant publishedAt, Instant createdAt) {
        jdbc.update("INSERT INTO outbox_event (event_id, aggregate_id, event_type, partition_key, payload, "
                        + "created_at, published_at) VALUES (?, ?, 'TRANSACTION_CREATED', 'c', '{}', ?, ?)",
                UUID.randomUUID().toString(), aggregateId, utc(createdAt), publishedAt == null ? null : utc(publishedAt));
    }

    /** Grava o instante em UTC, como o Hibernate faz com jdbc.time_zone=UTC. */
    private static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
