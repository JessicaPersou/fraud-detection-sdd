package com.persou.antifraud.notification.domain.exception;

/** Alerta fora do contrato FraudDetected. Não é retentado: vai direto para a DLQ. */
public class InvalidAlertException extends RuntimeException {

    public InvalidAlertException(String message) {
        super(message);
    }

    public InvalidAlertException(String message, Throwable cause) {
        super(message, cause);
    }
}
