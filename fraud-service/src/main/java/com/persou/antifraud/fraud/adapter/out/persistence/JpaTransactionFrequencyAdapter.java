package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Repository;

import com.persou.antifraud.fraud.application.port.out.TransactionFrequencyPort;

@Repository
class JpaTransactionFrequencyAdapter implements TransactionFrequencyPort {

    private final SpringDataTransactionHistoryRepository repository;

    JpaTransactionFrequencyAdapter(SpringDataTransactionHistoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(TransactionHistoryEntry entry) {
        repository.saveAndFlush(new TransactionHistoryJpaEntity(entry.transactionId(), entry.customerId(),
                entry.amountInCents(), entry.occurredAt()));
    }

    @Override
    public long countInWindow(String customerId, Instant end, Duration window) {
        return repository.countInWindow(customerId, end.minus(window), end);
    }
}
