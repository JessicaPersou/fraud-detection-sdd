package com.persou.antifraud.notification.application.port.out;

import java.util.function.Supplier;

/** Limite de transação de banco. A implementação concreta fica no adaptador. */
public interface UnitOfWork {

    <T> T execute(Supplier<T> work);
}
