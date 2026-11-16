package com.persou.antifraud.fraud.application.port.in;

import com.persou.antifraud.fraud.domain.model.RiskLevel;

/**
 * @param duplicate     true quando o evento já havia sido processado (inbox)
 * @param alertPublished true quando há alerta publicado para esta análise, nesta chamada ou em uma anterior
 */
public record AnalysisResult(RiskLevel riskLevel, boolean duplicate, boolean alertPublished) {
}
