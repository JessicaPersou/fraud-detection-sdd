package com.persou.antifraud.transaction.application.port.in;

/**
 * Resultado do recebimento.
 *
 * @param outcome {@link Outcome#CREATED} para transação nova (HTTP 201) e {@link Outcome#REPLAYED}
 *                para reenvio com resposta já guardada (HTTP 200)
 * @param responseBody JSON da resposta, guardado para reenvios
 */
public record ReceiveTransactionResult(Outcome outcome, String transactionId, String responseBody) {

    public enum Outcome {
        CREATED,
        REPLAYED
    }
}
