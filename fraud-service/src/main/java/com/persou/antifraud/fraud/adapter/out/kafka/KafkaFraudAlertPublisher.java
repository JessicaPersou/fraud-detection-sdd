package com.persou.antifraud.fraud.adapter.out.kafka;

import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.fraud.application.port.out.FraudAlertPublisher;
import com.persou.antifraud.fraud.config.FraudProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Publica {@code FraudDetected} em {@code fraud-alerts}, com {@code customerId} como chave (ADR 0005).
 * Falhas são propagadas: o retry do consumidor reenvia o mesmo eventId.
 */
@Component
class KafkaFraudAlertPublisher implements FraudAlertPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;

    KafkaFraudAlertPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                             FraudProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = properties.kafka().alertTopic();
    }

    @Override
    public void publish(FraudDetectedEvent event) {
        String payload = objectMapper.writeValueAsString(event);
        try {
            kafka.send(topic, event.customerId(), payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação de alerta interrompida", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao publicar alerta " + event.eventId(), e);
        }
    }
}
