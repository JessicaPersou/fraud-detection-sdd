package com.persou.antifraud.transaction.adapter.out.persistence;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.persou.antifraud.transaction.application.port.out.UnitOfWork;

@Component
class SpringUnitOfWork implements UnitOfWork {

    private final TransactionTemplate template;

    SpringUnitOfWork(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
