package com.persou.antifraud.transaction.adapter.in.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.persou.antifraud.transaction.domain.exception.IdempotencyKeyInProgressException;
import com.persou.antifraud.transaction.domain.exception.IdempotencyPayloadMismatchException;
import com.persou.antifraud.transaction.domain.exception.InvalidTransactionException;
import com.persou.antifraud.transaction.domain.exception.UnsupportedCurrencyException;

/**
 * Converte exceções em respostas HTTP. As mensagens são genéricas: nada de stack trace,
 * nomes de tabela ou SQL nas respostas (spec §7, V-04). Detalhes ficam apenas no log do servidor.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            InvalidTransactionException.class
    })
    ResponseEntity<ProblemDetail> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Dados de entrada inválidos");
    }

    @ExceptionHandler({
            UnsupportedCurrencyException.class,
            IdempotencyPayloadMismatchException.class
    })
    ResponseEntity<ProblemDetail> unprocessable(RuntimeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    ResponseEntity<ProblemDetail> conflict() {
        return problem(HttpStatus.CONFLICT, "Requisição em andamento; tente novamente");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> internalError(Exception e) {
        log.error("Erro não tratado ao processar requisição", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
