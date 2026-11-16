package com.persou.antifraud.notification.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, String> {
}
