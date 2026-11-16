package com.persou.antifraud.transaction.application.port.in;

/** Caso de uso de entrada: recebe uma transação com idempotência (spec §4). */
public interface ReceiveTransactionUseCase {

    ReceiveTransactionResult receive(ReceiveTransactionCommand command);
}
