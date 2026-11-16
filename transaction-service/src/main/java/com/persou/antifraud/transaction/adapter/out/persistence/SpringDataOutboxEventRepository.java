package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Criação do registro pendente mais antigo, para medir o atraso de publicação (R3). */
    @Query("select min(e.createdAt) from OutboxEventJpaEntity e where e.publishedAt is null")
    Instant findOldestPendingCreatedAt();

    /** Remove apenas registros já publicados antes do limite (R5). Pendentes nunca são removidos. */
    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt is not null and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
