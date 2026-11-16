package com.persou.antifraud.transaction.adapter.in.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTransactionRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotNull @Positive Long amountInCents,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}
