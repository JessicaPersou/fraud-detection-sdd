-- DDL consolidado de transaction_db (estado final após todas as migrações).
-- Gerado a partir de: transaction-service/src/main/resources/db/migration/transaction/V1__create_transaction_schema.sql, transaction-service/src/main/resources/db/migration/transaction/V2__add_outbox_trace_parent.sql
-- Fonte da verdade: os arquivos de migração (Flyway). Este arquivo é só para leitura e revisão.

USE transaction_db;

-- ===== transaction-service/src/main/resources/db/migration/transaction/V1__create_transaction_schema.sql =====
-- Schema do Transaction Service (transaction_db). ADR 0003, ADR 0004 e ADR 0001 (decisão 5).

CREATE TABLE transaction_record (
    transaction_id  VARCHAR(36)  NOT NULL PRIMARY KEY,
    customer_id     VARCHAR(64)  NOT NULL,
    amount_in_cents BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    INDEX idx_transaction_customer (customer_id)
);

CREATE TABLE idempotency_key (
    idempotency_key VARCHAR(255) NOT NULL PRIMARY KEY,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    transaction_id  VARCHAR(36)  NULL,
    response_body   TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    INDEX idx_idempotency_expires (expires_at)
);

CREATE TABLE outbox_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id      VARCHAR(36)  NOT NULL,
    aggregate_id  VARCHAR(36)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    partition_key VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    UNIQUE KEY uk_outbox_event_id (event_id),
    INDEX idx_outbox_published (published_at, id)
);

-- ===== transaction-service/src/main/resources/db/migration/transaction/V2__add_outbox_trace_parent.sql =====
-- Contexto de trace (W3C traceparent) gravado junto ao evento, para que a publicação assíncrona
-- continue o mesmo trace da requisição HTTP (ADR 0010).
ALTER TABLE outbox_event ADD COLUMN trace_parent VARCHAR(80) NULL;
