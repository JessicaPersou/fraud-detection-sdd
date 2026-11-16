package com.persou.antifraud.fraud.application.port.out;

import com.persou.antifraud.contracts.FraudDetectedEvent;

/**
 * Publica o alerta. Deve lançar exceção em falha, para que a etapa seja retentada com o mesmo eventId (spec §7, passo 8).
 */
public interface FraudAlertPublisher {

    void publish(FraudDetectedEvent event);
}
