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
