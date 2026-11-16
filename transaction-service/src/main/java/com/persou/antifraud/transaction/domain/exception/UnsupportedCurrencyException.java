package com.persou.antifraud.transaction.domain.exception;

/** Moeda fora da lista configurada (ADR 0006). Resulta em 422. */
public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException() {
        super("Moeda não suportada");
    }
}
