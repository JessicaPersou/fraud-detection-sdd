package com.persou.antifraud.notification.domain.exception;

/** O eventId já tem registro de notificação. Barreira final contra envio duplicado (N-04). */
public class DuplicateNotificationException extends RuntimeException {

    public DuplicateNotificationException() {
        super("Notificação já registrada para este alerta");
    }
}
