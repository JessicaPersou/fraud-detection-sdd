package com.persou.antifraud.notification.config;

import java.time.Clock;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.persou.antifraud.notification.application.port.in.ProcessFraudAlertUseCase;
import com.persou.antifraud.notification.application.port.out.NotificationProvider;
import com.persou.antifraud.notification.application.port.out.NotificationRepository;
import com.persou.antifraud.notification.application.port.out.UnitOfWork;
import com.persou.antifraud.notification.application.usecase.ProcessFraudAlertService;
import com.persou.antifraud.notification.domain.exception.InvalidAlertException;

@Configuration
@ConfigurationPropertiesScan
class NotificationServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProcessFraudAlertUseCase processFraudAlertUseCase(NotificationRepository repository,
                                                      NotificationProvider provider,
                                                      UnitOfWork unitOfWork,
                                                      Clock clock,
                                                      NotificationProperties properties) {
        return new ProcessFraudAlertService(repository, provider, unitOfWork, clock,
                properties.channel(), properties.recipient());
    }

    /**
     * Retry com backoff de 1 s, 2 s e 4 s (ADR 0005, seção 3). Alerta inválido vai direto para a DLQ.
     * Após esgotar as tentativas, a mensagem vai para {@code fraud-alerts.DLT}.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidAlertException.class);
        return handler;
    }
}
