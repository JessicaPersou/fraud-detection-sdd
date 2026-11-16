package com.persou.antifraud.fraud.domain.exception;

/**
 * Evento com contrato inválido. Não é retentado: vai direto para a DLQ (ADR 0005, seção 3).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
