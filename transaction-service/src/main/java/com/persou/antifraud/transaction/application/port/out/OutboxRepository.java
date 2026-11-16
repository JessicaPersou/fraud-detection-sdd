package com.persou.antifraud.transaction.application.port.out;

public interface OutboxRepository {

    void save(OutboxMessage message);
}
