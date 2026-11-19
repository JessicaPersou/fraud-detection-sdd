package com.persou.antifraud.notification.application.port.out;

/**
 * Mensagem enviada ao provedor. {@code body} é o texto puro (fallback) e {@code htmlBody} a versão em HTML.
 * Nenhum dos dois contém dados pessoais além dos identificadores do alerta.
 */
public record NotificationMessage(
        String eventId,
        String channel,
        String recipient,
        String subject,
        String body,
        String htmlBody) {
}
