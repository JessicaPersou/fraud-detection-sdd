package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataIdempotencyKeyRepository extends JpaRepository<IdempotencyKeyJpaEntity, String> {
}
