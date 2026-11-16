package com.persou.antifraud.fraud.application.port.out;

import java.time.Instant;

public interface InboxRepository {

    boolean exists(String eventId);

    /**
     * @throws com.persou.antifraud.fraud.domain.exception.DuplicateEventException se o eventId já existir
     */
    void insert(String eventId, Instant processedAt);
}
