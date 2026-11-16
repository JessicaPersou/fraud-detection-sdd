package com.persou.antifraud.fraud.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.persou.antifraud.fraud.domain.model.RiskLevel;
import com.persou.antifraud.fraud.domain.model.RuleFinding;

/** Valores de teste, não de negócio: limite de valor 100000 centavos, até 5 transações em 60 s. */
class FraudRuleEvaluatorTest {

    private static final long THRESHOLD = 100_000;
    private static final int MAX_TRANSACTIONS = 5;
    private static final long WINDOW_SECONDS = 60;

    private final FraudRuleEvaluator evaluator =
            new FraudRuleEvaluator(THRESHOLD, MAX_TRANSACTIONS, WINDOW_SECONDS);

    @Test
    void amountAboveThresholdTriggersHighTransactionAmountWithExplanation() {
        List<RuleFinding> findings = evaluator.evaluate(THRESHOLD + 1, 1);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleCode()).isEqualTo("HIGH_TRANSACTION_AMOUNT");
        assertThat(findings.get(0).description()).isNotBlank();
        assertThat(findings.get(0).reason()).contains(String.valueOf(THRESHOLD + 1));
    }

    @Test
    void amountEqualToThresholdDoesNotTrigger() {
        assertThat(evaluator.evaluate(THRESHOLD, 1)).isEmpty();
    }

    @Test
    void frequencyAtLimitDoesNotTriggerAndAboveLimitDoes() {
        assertThat(evaluator.evaluate(10, MAX_TRANSACTIONS)).isEmpty();

        List<RuleFinding> findings = evaluator.evaluate(10, MAX_TRANSACTIONS + 1);
        assertThat(findings).extracting(RuleFinding::ruleCode).containsExactly("TRANSACTION_FREQUENCY");
        assertThat(findings.get(0).reason()).contains("limite configurado: " + MAX_TRANSACTIONS);
    }

    @Test
    void bothRulesCanTriggerTogether() {
        List<RuleFinding> findings = evaluator.evaluate(THRESHOLD + 1, MAX_TRANSACTIONS + 1);

        assertThat(findings).extracting(RuleFinding::ruleCode)
                .containsExactlyInAnyOrder("HIGH_TRANSACTION_AMOUNT", "TRANSACTION_FREQUENCY");
    }

    @ParameterizedTest(name = "{0} sinal(is) -> {1}")
    @CsvSource({"0,LOW", "1,MEDIUM", "2,HIGH"})
    void classificationFollowsProvisionalPolicy(int signals, RiskLevel expected) {
        List<RuleFinding> findings = java.util.stream.IntStream.range(0, signals)
                .mapToObj(i -> new RuleFinding("R" + i, "d", "r"))
                .toList();

        assertThat(RiskClassifier.classify(findings)).isEqualTo(expected);
    }
}
