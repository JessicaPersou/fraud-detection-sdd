package com.persou.antifraud.fraud.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataInboxRepository extends JpaRepository<InboxEventJpaEntity, String> {

    /** Remove registros processados antes do limite de retenção (ADR 0005, seção 4). */
    @Modifying
    @Query("delete from InboxEventJpaEntity e where e.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
