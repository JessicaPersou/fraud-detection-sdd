package com.persou.antifraud.transaction.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainModelTest {

    @Test
    void sameInputProducesSameFingerprint() {
        assertThat(RequestFingerprint.of("123", 150_000, "BRL"))
                .isEqualTo(RequestFingerprint.of("123", 150_000, "BRL"));
    }

    @Test
    void anyChangeInPayloadChangesFingerprint() {
        String base = RequestFingerprint.of("123", 150_000, "BRL");
        assertThat(RequestFingerprint.of("124", 150_000, "BRL")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("123", 150_001, "BRL")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("123", 150_000, "JPY")).isNotEqualTo(base);
    }

    @Test
    void fingerprintIsUnambiguousForCustomerIdsThatLookLikeSeparators() {
        assertThat(RequestFingerprint.of("1|2", 3, "BRL")).isNotEqualTo(RequestFingerprint.of("1", 2, "|3|BRL"));
    }

    @ParameterizedTest
    @CsvSource({"BRL,2", "JPY,0", "KWD,3"})
    void minorUnitDigitsFollowIso4217(String currency, int digits) {
        assertThat(CurrencyCatalog.minorUnitDigits(currency)).contains(digits);
    }

    @Test
    void unknownCurrencyHasNoExponent() {
        assertThat(CurrencyCatalog.minorUnitDigits("XYZ")).isEmpty();
    }
}
