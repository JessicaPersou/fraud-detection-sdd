package com.persou.antifraud.transaction.adapter.out.persistence;

import org.springframework.stereotype.Component;

import com.persou.antifraud.transaction.application.port.out.PayloadSerializer;

import tools.jackson.databind.ObjectMapper;

@Component
class JacksonPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper objectMapper;

    JacksonPayloadSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toJson(Object payload) {
        return objectMapper.writeValueAsString(payload);
    }
}
