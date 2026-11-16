package com.persou.antifraud.transaction.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash SHA-256 do corpo normalizado da requisição (ADR 0004).
 * Separa reenvio legítimo (mesmo hash) de reutilização indevida da chave (hash diferente).
 *
 * <p>O {@code customerId} é prefixado pelo seu tamanho para que valores diferentes nunca
 * produzam a mesma string canônica.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String customerId, long amountInCents, String currency) {
        String canonical = customerId.length() + ":" + customerId + "|" + amountInCents + "|" + currency;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
