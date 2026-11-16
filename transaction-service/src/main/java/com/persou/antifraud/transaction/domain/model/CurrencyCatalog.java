package com.persou.antifraud.transaction.domain.model;

import java.util.Map;
import java.util.Optional;

/**
 * Expoentes ISO 4217 (casas decimais da menor unidade) usados para interpretar {@code amountInCents}.
 * Subconjunto suficiente para validar moedas configuradas e testar expoentes 0, 2 e 3 (ADR 0006).
 */
public final class CurrencyCatalog {

    private static final Map<String, Integer> MINOR_UNIT_DIGITS = Map.of(
            "BRL", 2,
            "JPY", 0,
            "KWD", 3);

    private CurrencyCatalog() {
    }

    public static Optional<Integer> minorUnitDigits(String currencyCode) {
        return Optional.ofNullable(MINOR_UNIT_DIGITS.get(currencyCode));
    }
}
