package com.persou.antifraud.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros do Notification Service. Canal e destinatário são de configuração e não têm padrão
 * (ADR 0001, decisão 10): o serviço não sobe sem eles.
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(String channel, String recipient, Kafka kafka) {

    public record Kafka(String alertTopic, String consumerGroup) {
    }
}
