package com.persou.antifraud.fraud.domain.service;

import java.util.List;

import com.persou.antifraud.fraud.domain.model.RiskLevel;
import com.persou.antifraud.fraud.domain.model.RuleFinding;

/**
 * Política provisória de classificação (business-rules.md §3 e BR-04).
 *
 * <ul>
 *   <li>Nenhum sinal: LOW.</li>
 *   <li>Um sinal: MEDIUM (valor elevado isoladamente é MEDIUM, conforme decisão preliminar).</li>
 *   <li>Dois ou mais sinais: HIGH. Provisório: a combinação e os pesos dependem de validação de negócio.</li>
 * </ul>
 */
public final class RiskClassifier {

    private RiskClassifier() {
    }

    public static RiskLevel classify(List<RuleFinding> findings) {
        return switch (findings.size()) {
            case 0 -> RiskLevel.LOW;
            case 1 -> RiskLevel.MEDIUM;
            default -> RiskLevel.HIGH;
        };
    }
}
