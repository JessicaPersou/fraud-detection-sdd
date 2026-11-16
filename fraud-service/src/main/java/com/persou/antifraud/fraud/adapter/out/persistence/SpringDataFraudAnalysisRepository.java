package com.persou.antifraud.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataFraudAnalysisRepository extends JpaRepository<FraudAnalysisJpaEntity, String> {
}
