package com.persou.antifraud.fraud.application.port.in;

import com.persou.antifraud.contracts.TransactionCreatedEvent;

/** Caso de uso de entrada: analisa uma transação recebida via {@code transaction-events} (spec §3). */
public interface AnalyzeTransactionUseCase {

    AnalysisResult analyze(TransactionCreatedEvent event);
}
