package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataTransactionHistoryRepository extends JpaRepository<TransactionHistoryJpaEntity, String> {

    @Query("select count(h) from TransactionHistoryJpaEntity h "
            + "where h.customerId = :customerId and h.occurredAt between :from and :to")
    long countInWindow(@Param("customerId") String customerId,
                       @Param("from") Instant from,
                       @Param("to") Instant to);
}
