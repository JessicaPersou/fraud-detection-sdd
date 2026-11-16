package com.persou.antifraud.notification.application.port.out;

/**
 * Estados distintos (spec §7, N-03).
 *
 * <ul>
 *   <li>{@code PENDING}: registro criado, envio em andamento ou interrompido antes do aceite.</li>
 *   <li>{@code ACCEPTED_BY_PROVIDER}: o provedor aceitou a notificação. Não garante entrega.</li>
 *   <li>{@code FAILED}: tentativa falhou. Após as tentativas do consumidor, a mensagem vai para a DLQ.</li>
 *   <li>{@code DELIVERED}: reservado. Exige confirmação de entrega do provedor, ainda não implementada.</li>
 * </ul>
 */
public enum NotificationStatus {
    PENDING,
    ACCEPTED_BY_PROVIDER,
    FAILED,
    DELIVERED
}
