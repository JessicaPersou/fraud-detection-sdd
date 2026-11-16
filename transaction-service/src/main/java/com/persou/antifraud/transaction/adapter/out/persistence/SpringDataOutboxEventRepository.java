package com.persou.antifraud.transaction.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataOutboxEventRepository extends JpaRepository<OutboxEventJpaEntity, Long> {

    /**
     * Lote de pendentes travado com {@code SKIP LOCKED}: várias instâncias do publicador
     * não publicam o mesmo evento ao mesmo tempo (ADR 0003).
     */
    @Query(value = "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventJpaEntity> lockPending(@Param("limit") int limit);
}
