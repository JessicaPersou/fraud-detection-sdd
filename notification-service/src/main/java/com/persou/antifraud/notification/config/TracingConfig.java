package com.persou.antifraud.notification.config;

import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exporta os spans para o console (ADR 0010). Para a demonstração basta acompanhar o log do serviço:
 * cada transação aparece com o mesmo traceId desde o HTTP até o consumidor seguinte.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    @Bean
    SpanExporter loggingSpanExporter() {
        return LoggingSpanExporter.create();
    }
}
