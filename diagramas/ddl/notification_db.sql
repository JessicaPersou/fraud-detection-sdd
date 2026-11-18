-- DDL consolidado de notification_db (estado final após todas as migrações).
-- Gerado a partir de: notification-service/src/main/resources/db/migration/notification/V1__create_notification_schema.sql
-- Fonte da verdade: os arquivos de migração (Flyway). Este arquivo é só para leitura e revisão.

USE notification_db;

-- ===== notification-service/src/main/resources/db/migration/notification/V1__create_notification_schema.sql =====
-- Schema do Notification Service (notification_db). Spec §7 e N-03/N-04.

CREATE TABLE notification_record (
    event_id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    transaction_id     VARCHAR(36)  NOT NULL,
    customer_id        VARCHAR(64)  NOT NULL,
    risk_level         VARCHAR(10)  NOT NULL,
    channel            VARCHAR(32)  NOT NULL,
    recipient          VARCHAR(255) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    provider_reference VARCHAR(128) NULL,
    attempts           INT          NOT NULL DEFAULT 0,
    last_error         VARCHAR(255) NULL,
    updated_at         DATETIME(6)  NOT NULL,
    INDEX idx_notification_status (status)
);
