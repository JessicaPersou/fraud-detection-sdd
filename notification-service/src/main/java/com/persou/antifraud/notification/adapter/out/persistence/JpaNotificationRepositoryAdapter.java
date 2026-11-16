package com.persou.antifraud.notification.adapter.out.persistence;

import java.time.Clock;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.notification.application.port.out.NotificationRecord;
import com.persou.antifraud.notification.application.port.out.NotificationRepository;
import com.persou.antifraud.notification.domain.exception.DuplicateNotificationException;

@Repository
class JpaNotificationRepositoryAdapter implements NotificationRepository {

    private final SpringDataNotificationRepository repository;
    private final Clock clock;

    JpaNotificationRepositoryAdapter(SpringDataNotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<NotificationRecord> findByEventId(String eventId) {
        return repository.findById(eventId).map(NotificationJpaEntity::toRecord);
    }

    @Override
    public void insertPending(NotificationRecord record) {
        try {
            repository.saveAndFlush(new NotificationJpaEntity(record));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateNotificationException();
        }
    }

    @Override
    @Transactional
    public void registerAttempt(String eventId) {
        repository.findById(eventId).ifPresent(e -> e.registerAttempt(clock.instant()));
    }

    @Override
    @Transactional
    public void markAccepted(String eventId, String providerReference) {
        repository.findById(eventId).ifPresent(e -> e.markAccepted(providerReference, clock.instant()));
    }

    @Override
    @Transactional
    public void markFailed(String eventId, String error) {
        repository.findById(eventId).ifPresent(e -> e.markFailed(error, clock.instant()));
    }
}
