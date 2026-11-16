package com.persou.antifraud.notification.application.port.out;

/** Falha ao falar com o provedor. Transitória por padrão: o consumidor retenta e, se persistir, envia à DLQ. */
public class NotificationProviderException extends RuntimeException {

    public NotificationProviderException(String message) {
        super(message);
    }
}
