package com.persou.antifraud.notification.application.port.out;

import java.util.Optional;

public interface NotificationRepository {

    Optional<NotificationRecord> findByEventId(String eventId);

    /**
     * @throws com.persou.antifraud.notification.domain.exception.DuplicateNotificationException se o eventId já existir
     */
    void insertPending(NotificationRecord record);

    void registerAttempt(String eventId);

    void markAccepted(String eventId, String providerReference);

    void markFailed(String eventId, String error);
}
