package com.persou.antifraud.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataInboxRepository extends JpaRepository<InboxEventJpaEntity, String> {
}
