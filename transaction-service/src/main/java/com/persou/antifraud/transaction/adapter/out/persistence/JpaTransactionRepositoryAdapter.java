package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.stereotype.Repository;

import com.persou.antifraud.transaction.application.port.out.TransactionRepository;
import com.persou.antifraud.transaction.domain.model.Transaction;

@Repository
class JpaTransactionRepositoryAdapter implements TransactionRepository {

    private final SpringDataTransactionRepository repository;

    JpaTransactionRepositoryAdapter(SpringDataTransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Transaction transaction) {
        repository.save(new TransactionJpaEntity(transaction));
    }
}
