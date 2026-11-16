package com.persou.antifraud.fraud.domain.exception;

/** O eventId já existe na inbox. Barreira final contra processamento duplicado (ADR 0005, seção 4). */
public class DuplicateEventException extends RuntimeException {

    public DuplicateEventException() {
        super("Evento já processado");
    }
}
