package com.persou.antifraud.fraud.config;

import java.time.Clock;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

import com.persou.antifraud.fraud.application.port.in.AnalyzeTransactionUseCase;
import com.persou.antifraud.fraud.application.port.out.FraudAlertPublisher;
import com.persou.antifraud.fraud.application.port.out.FraudAnalysisRepository;
import com.persou.antifraud.fraud.application.port.out.InboxRepository;
import com.persou.antifraud.fraud.application.port.out.TransactionFrequencyPort;
import com.persou.antifraud.fraud.application.port.out.UnitOfWork;
import com.persou.antifraud.fraud.application.usecase.AnalyzeTransactionService;
import com.persou.antifraud.fraud.domain.exception.InvalidEventException;
import com.persou.antifraud.fraud.domain.service.FraudRuleEvaluator;

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan
class FraudServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AnalyzeTransactionUseCase analyzeTransactionUseCase(FraudProperties properties,
                                                        InboxRepository inbox,
                                                        FraudAnalysisRepository analyses,
                                                        TransactionFrequencyPort frequency,
                                                        FraudAlertPublisher alertPublisher,
                                                        UnitOfWork unitOfWork,
                                                        Clock clock) {
        FraudProperties.Rules rules = properties.rules();
        FraudRuleEvaluator evaluator = new FraudRuleEvaluator(rules.amount().thresholdCents(),
                rules.frequency().maxTransactions(), rules.frequency().window().toSeconds());
        return new AnalyzeTransactionService(evaluator, rules.frequency().window(), inbox, analyses, frequency,
                alertPublisher, unitOfWork, clock);
    }

    /**
     * Retry com backoff de 1 s, 2 s e 4 s (ADR 0005, seção 3). Payload inválido vai direto para a DLQ.
     * Após esgotar as tentativas, a mensagem vai para {@code <topic>.DLT} com os cabeçalhos de origem.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
