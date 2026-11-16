package com.persou.antifraud.fraud.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.fraud.application.port.out.FraudAnalysisRepository;
import com.persou.antifraud.fraud.application.port.out.StoredAnalysis;
import com.persou.antifraud.fraud.domain.model.FraudAnalysis;
import com.persou.antifraud.fraud.domain.model.RiskLevel;
import com.persou.antifraud.fraud.domain.model.RuleFinding;

import tools.jackson.databind.ObjectMapper;

@Repository
class JpaFraudAnalysisAdapter implements FraudAnalysisRepository {

    private final SpringDataFraudAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    JpaFraudAnalysisAdapter(SpringDataFraudAnalysisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(FraudAnalysis analysis) {
        repository.saveAndFlush(new FraudAnalysisJpaEntity(
                analysis.sourceEventId(),
                analysis.alertEventId(),
                analysis.transactionId(),
                analysis.customerId(),
                analysis.riskLevel().name(),
                objectMapper.writeValueAsString(analysis.findings()),
                analysis.analyzedAt(),
                false));
    }

    @Override
    public Optional<StoredAnalysis> findBySourceEventId(String sourceEventId) {
        return repository.findById(sourceEventId).map(this::toStored);
    }

    /** Transacional: sem ela, a entidade carregada fica desanexada e a mudança não é gravada. */
    @Override
    @Transactional
    public void markAlertPublished(String sourceEventId) {
        repository.findById(sourceEventId).ifPresent(FraudAnalysisJpaEntity::markAlertPublished);
    }

    private StoredAnalysis toStored(FraudAnalysisJpaEntity entity) {
        List<RuleFinding> findings = List.of(objectMapper.readValue(entity.getTriggeredRules(),
                RuleFinding[].class));
        FraudAnalysis analysis = new FraudAnalysis(entity.getSourceEventId(), entity.getAlertEventId(),
                entity.getTransactionId(), entity.getCustomerId(), RiskLevel.valueOf(entity.getRiskLevel()),
                findings, entity.getAnalyzedAt());
        return new StoredAnalysis(analysis, entity.isAlertPublished());
    }
}
