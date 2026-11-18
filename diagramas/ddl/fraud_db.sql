-- DDL consolidado de fraud_db (estado final após todas as migrações).
-- Gerado a partir de: fraud-service/src/main/resources/db/migration/fraud/V1__create_fraud_schema.sql
-- Fonte da verdade: os arquivos de migração (Flyway). Este arquivo é só para leitura e revisão.

USE fraud_db;

-- ===== fraud-service/src/main/resources/db/migration/fraud/V1__create_fraud_schema.sql =====
-- Schema do Fraud Detection Service (fraud_db). ADR 0005 (inbox, retenção) e spec §7.

CREATE TABLE inbox_event (
    event_id     VARCHAR(36)  NOT NULL PRIMARY KEY,
    processed_at DATETIME(6)  NOT NULL
);

CREATE TABLE fraud_analysis (
    source_event_id VARCHAR(36)  NOT NULL PRIMARY KEY,
    alert_event_id  VARCHAR(36)  NOT NULL UNIQUE,
    transaction_id  VARCHAR(36)  NOT NULL UNIQUE,
    customer_id     VARCHAR(64)  NOT NULL,
    risk_level      VARCHAR(10)  NOT NULL,
    triggered_rules TEXT         NOT NULL,
    analyzed_at     DATETIME(6)  NOT NULL,
    alert_published BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE transaction_history (
    transaction_id  VARCHAR(36)  NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    amount_in_cents BIGINT       NOT NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    INDEX idx_history_customer_time (customer_id, occurred_at)
);
