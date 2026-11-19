package com.persou.antifraud.notification.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.contracts.FraudDetectedEvent.TriggeredRule;

class AlertEmailTemplateTest {

    private static final FraudDetectedEvent EVENT = FraudDetectedEvent.create(
            "evt-1", "tx-123", "customer-SECRET", "MEDIUM",
            List.of(new TriggeredRule("TRANSACTION_FREQUENCY", "Volume acima do limite",
                    "6 transações em 60 segundos; limite configurado: 5")),
            Instant.parse("2026-11-19T01:31:20Z"));

    @Test
    void textVersionHasTransactionRiskDateAndRules() {
        String text = AlertEmailTemplate.text(EVENT);

        assertThat(text).contains("Transação: tx-123", "Nível de risco: Médio", "18/11/2026 às 22:31");
        assertThat(text).contains("- Volume acima do limite: 6 transações em 60 segundos; limite configurado: 5");
    }

    @Test
    void htmlVersionHasBadgeAndRulesAndNeverExposesCustomerId() {
        String html = AlertEmailTemplate.html(EVENT);

        assertThat(html).contains("<!doctype html>", "tx-123", "Médio", "Volume acima do limite");
        assertThat(html).doesNotContain("customer-SECRET");
        assertThat(AlertEmailTemplate.text(EVENT)).doesNotContain("customer-SECRET");
    }

    @Test
    void userProvidedTextIsEscapedInHtml() {
        FraudDetectedEvent malicious = FraudDetectedEvent.create(
                "evt-2", "tx-<script>", "c", "HIGH",
                List.of(new TriggeredRule("R", "<b>x</b> & \"y\"", "'z'")),
                Instant.parse("2026-11-19T01:31:20Z"));

        String html = AlertEmailTemplate.html(malicious);

        assertThat(html).doesNotContain("<script>", "<b>x</b>");
        assertThat(html).contains("tx-&lt;script&gt;", "&lt;b&gt;x&lt;/b&gt; &amp; &quot;y&quot;", "&#39;z&#39;");
    }
}
