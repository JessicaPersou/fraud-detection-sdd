package com.persou.antifraud.transaction.config;

import java.time.Clock;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionUseCase;
import com.persou.antifraud.transaction.application.port.out.IdempotencyRepository;
import com.persou.antifraud.transaction.application.port.out.OutboxRepository;
import com.persou.antifraud.transaction.application.port.out.PayloadSerializer;
import com.persou.antifraud.transaction.application.port.out.TransactionRepository;
import com.persou.antifraud.transaction.application.port.out.UnitOfWork;
import com.persou.antifraud.transaction.application.usecase.ReceiveTransactionService;

@Configuration
@EnableScheduling
@ConfigurationPropertiesScan
class TransactionServiceConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReceiveTransactionUseCase receiveTransactionUseCase(IdempotencyRepository idempotency,
                                                        TransactionRepository transactions,
                                                        OutboxRepository outbox,
                                                        UnitOfWork unitOfWork,
                                                        PayloadSerializer serializer,
                                                        Clock clock,
                                                        TransactionProperties properties) {
        return new ReceiveTransactionService(idempotency, transactions, outbox, unitOfWork, serializer, clock,
                properties.supportedCurrencies(), properties.idempotency().inProgressTimeout());
    }
}
