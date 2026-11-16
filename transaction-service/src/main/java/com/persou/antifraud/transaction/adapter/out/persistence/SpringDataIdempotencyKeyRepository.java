package com.persou.antifraud.transaction.adapter.out.persistence;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataIdempotencyKeyRepository extends JpaRepository<IdempotencyKeyJpaEntity, String> {

    @Modifying
    @Query("delete from IdempotencyKeyJpaEntity e where e.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
