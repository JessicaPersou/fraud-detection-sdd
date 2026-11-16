package com.persou.antifraud.fraud.application.port.out;

import java.time.Duration;
import java.time.Instant;

/** Histórico de transações por cliente, usado pela regra BR-01 (spec §5, ADR 0001 decisão 4). */
public interface TransactionFrequencyPort {

    void record(TransactionHistoryEntry entry);

    /** Conta transações do cliente em {@code [end - window, end]}, com os dois limites incluídos. */
    long countInWindow(String customerId, Instant end, Duration window);

    record TransactionHistoryEntry(String transactionId, String customerId, long amountInCents,
                                   Instant occurredAt) {
    }
}
