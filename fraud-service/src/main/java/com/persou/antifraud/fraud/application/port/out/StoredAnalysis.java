package com.persou.antifraud.fraud.application.port.out;

import com.persou.antifraud.fraud.domain.model.FraudAnalysis;

/** Análise persistida e o estado de publicação do alerta correspondente. */
public record StoredAnalysis(FraudAnalysis analysis, boolean alertPublished) {
}
