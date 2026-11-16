package com.persou.antifraud.notification.application.port.in;

import com.persou.antifraud.contracts.FraudDetectedEvent;

/** Caso de uso de entrada: processa um alerta antifraude (spec §3). */
public interface ProcessFraudAlertUseCase {

    ProcessingResult process(FraudDetectedEvent event);

    /**
     * @param duplicate true quando a notificação já havia sido aceita pelo provedor (N-04)
     */
    record ProcessingResult(boolean duplicate, String providerReference) {
    }
}
