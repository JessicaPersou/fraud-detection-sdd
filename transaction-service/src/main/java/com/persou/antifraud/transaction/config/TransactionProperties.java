package com.persou.antifraud.transaction.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do Transaction Service. Valores de negócio não têm padrão no código.
 * Parâmetros operacionais têm padrão em application.yml, documentado no README.
 */
@ConfigurationProperties(prefix = "transaction")
public record TransactionProperties(
        Set<String> supportedCurrencies,
        Idempotency idempotency,
        Outbox outbox,
        Retention retention) {

    public record Idempotency(Duration inProgressTimeout) {
    }

    public record Outbox(long pollIntervalMs, int batchSize, String topic) {
    }

    /**
     * @param publishedOutbox tempo que um registro publicado do outbox é mantido antes da limpeza (R5)
     * @param cleanupIntervalMs intervalo do job de limpeza
     */
    public record Retention(Duration publishedOutbox, long cleanupIntervalMs) {
    }
}
