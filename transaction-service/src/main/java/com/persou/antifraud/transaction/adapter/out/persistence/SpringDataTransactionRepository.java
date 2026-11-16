package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTransactionRepository extends JpaRepository<TransactionJpaEntity, String> {
}
