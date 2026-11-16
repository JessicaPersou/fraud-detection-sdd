package com.persou.antifraud.transaction.application.port.out;

import com.persou.antifraud.transaction.domain.model.Transaction;

public interface TransactionRepository {

    void save(Transaction transaction);
}
