package com.persou.antifraud.fraud.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do Fraud Detection Service. Limites de negócio não têm padrão no código
 * (ADR 0001, decisão 10): a aplicação não sobe sem eles.
 */
@ConfigurationProperties(prefix = "fraud")
public record FraudProperties(Rules rules, Kafka kafka, Retention retention) {

    /**
     * @param inbox retenção da inbox, maior que a do tópico (ADR 0005, seção 4)
     * @param cleanupIntervalMs intervalo do job de limpeza
     */
    public record Retention(Duration inbox, long cleanupIntervalMs) {
    }

    public record Rules(Amount amount, Frequency frequency) {
    }

    /** BR-02. Limite em centavos. */
    public record Amount(long thresholdCents) {
    }

    /** BR-01. Mais de {@code maxTransactions} na janela {@code window} aciona a regra. */
    public record Frequency(int maxTransactions, Duration window) {
    }

    public record Kafka(String transactionTopic, String alertTopic, String consumerGroup) {
    }
}
