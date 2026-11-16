package com.persou.antifraud.notification.application.port.out;

/**
 * Porta para o provedor de notificação. O domínio não depende de SDK de fornecedor (spec §5).
 * Falhas são lançadas como {@link NotificationProviderException} e são retentadas pelo consumidor.
 */
public interface NotificationProvider {

    ProviderReceipt send(NotificationMessage message);

    record ProviderReceipt(String reference) {
    }
}
