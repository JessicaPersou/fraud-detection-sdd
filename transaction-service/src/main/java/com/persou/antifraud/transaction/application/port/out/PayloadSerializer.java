package com.persou.antifraud.transaction.application.port.out;

public interface PayloadSerializer {

    String toJson(Object payload);
}
