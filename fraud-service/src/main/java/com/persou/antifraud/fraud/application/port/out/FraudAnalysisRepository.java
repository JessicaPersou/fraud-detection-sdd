package com.persou.antifraud.fraud.application.port.out;

import java.util.Optional;

import com.persou.antifraud.fraud.domain.model.FraudAnalysis;

public interface FraudAnalysisRepository {

    /** Grava a análise com o alerta ainda não publicado. */
    void save(FraudAnalysis analysis);

    Optional<StoredAnalysis> findBySourceEventId(String sourceEventId);

    void markAlertPublished(String sourceEventId);
}
