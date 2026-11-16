package com.persou.antifraud.fraud.adapter.in.kafka;

import java.time.Clock;
import java.time.Duration;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.persou.antifraud.contracts.TransactionCreatedEvent;
import com.persou.antifraud.fraud.application.port.in.AnalysisResult;
import com.persou.antifraud.fraud.application.port.in.AnalyzeTransactionUseCase;
import com.persou.antifraud.fraud.domain.exception.InvalidEventException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de {@code transaction-events}. Offset confirmado após o retorno do caso de uso
 * (ack-mode record, enable-auto-commit false; ADR 0001, decisão 8).
 *
 * <p>Registra a latência ponta a ponta de todas as transações, desde o {@code occurredAt} (recebimento). Para
 * transações com alerta, o término é a publicação do alerta (`fraud.alert.latency`); para as LOW, a gravação
 * da análise (`fraud.analysis.latency`). É a medição do SLA de 500 ms (ADR 0008, seção 2.8).
 */
@Component
class TransactionCreatedListener {

    static final String LATENCY_METRIC = "fraud.alert.latency";
    static final String ANALYSIS_LATENCY_METRIC = "fraud.analysis.latency";

    private final AnalyzeTransactionUseCase analyze;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Timer alertLatency;
    private final Timer analysisLatency;

    TransactionCreatedListener(AnalyzeTransactionUseCase analyze, ObjectMapper objectMapper, Clock clock,
                               MeterRegistry meterRegistry) {
        this.analyze = analyze;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.alertLatency = Timer.builder(LATENCY_METRIC)
                .description("Tempo do recebimento da transação até a publicação do alerta")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.analysisLatency = Timer.builder(ANALYSIS_LATENCY_METRIC)
                .description("Tempo do recebimento até a gravação da análise de transações LOW (sem alerta)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${fraud.kafka.transaction-topic}", groupId = "${fraud.kafka.consumer-group}")
    void onTransactionCreated(String payload) {
        TransactionCreatedEvent event = parse(payload);
        AnalysisResult result = analyze.analyze(event);
        if (event.occurredAt() == null || result.duplicate()) {
            return;
        }
        Duration elapsed = Duration.between(event.occurredAt(), clock.instant());
        if (result.alertPublished()) {
            alertLatency.record(elapsed);
        } else {
            analysisLatency.record(elapsed);
        }
    }

    private TransactionCreatedEvent parse(String payload) {
        if (payload == null) {
            throw new InvalidEventException("Payload nulo");
        }
        try {
            return objectMapper.readValue(payload, TransactionCreatedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Payload fora do contrato TransactionCreated", e);
        }
    }
}
