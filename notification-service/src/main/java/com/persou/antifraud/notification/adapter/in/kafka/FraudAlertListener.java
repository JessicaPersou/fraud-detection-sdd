package com.persou.antifraud.notification.adapter.in.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.persou.antifraud.contracts.FraudDetectedEvent;
import com.persou.antifraud.notification.application.port.in.ProcessFraudAlertUseCase;
import com.persou.antifraud.notification.domain.exception.InvalidAlertException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de {@code fraud-alerts}. Offset confirmado após o retorno do caso de uso (ADR 0001, decisão 8).
 */
@Component
class FraudAlertListener {

    private final ProcessFraudAlertUseCase process;
    private final ObjectMapper objectMapper;

    FraudAlertListener(ProcessFraudAlertUseCase process, ObjectMapper objectMapper) {
        this.process = process;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${notification.kafka.alert-topic}", groupId = "${notification.kafka.consumer-group}")
    void onFraudDetected(String payload) {
        process.process(parse(payload));
    }

    private FraudDetectedEvent parse(String payload) {
        if (payload == null) {
            throw new InvalidAlertException("Payload nulo");
        }
        try {
            return objectMapper.readValue(payload, FraudDetectedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidAlertException("Payload fora do contrato FraudDetected", e);
        }
    }
}
