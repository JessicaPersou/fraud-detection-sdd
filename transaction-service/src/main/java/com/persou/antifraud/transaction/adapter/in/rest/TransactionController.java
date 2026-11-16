package com.persou.antifraud.transaction.adapter.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionCommand;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionResult;
import com.persou.antifraud.transaction.application.port.in.ReceiveTransactionUseCase;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/transactions")
class TransactionController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReceiveTransactionUseCase receiveTransaction;

    TransactionController(ReceiveTransactionUseCase receiveTransaction) {
        this.receiveTransaction = receiveTransaction;
    }

    /** 201 para transação nova; 200 para reenvio com a mesma chave e o mesmo payload (ADR 0004). */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> create(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {
        ReceiveTransactionResult result = receiveTransaction.receive(new ReceiveTransactionCommand(
                idempotencyKey, request.customerId(), request.amountInCents(), request.currency()));

        HttpStatus status = result.outcome() == ReceiveTransactionResult.Outcome.CREATED
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }
}
