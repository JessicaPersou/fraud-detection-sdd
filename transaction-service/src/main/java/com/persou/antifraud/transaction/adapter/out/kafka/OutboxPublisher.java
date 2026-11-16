package com.persou.antifraud.transaction.adapter.out.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.persou.antifraud.transaction.adapter.out.persistence.OutboxEventJpaEntity;
import com.persou.antifraud.transaction.adapter.out.persistence.SpringDataOutboxEventRepository;
import com.persou.antifraud.transaction.config.TransactionProperties;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * Publicador do outbox por polling (ADR 0003).
 *
 * <p>Em falha, o registro permanece pendente e é reenviado com o mesmo {@code eventId}.
 * A lista é processada em ordem e interrompida no primeiro erro, para preservar a ordem por cliente.
 * O marcador de publicado só é gravado após o ack do Kafka.
 *
 * <p>Retoma o trace gravado no evento (ADR 0010): o envio acontece dentro de um span filho do
 * span da requisição original, então o consumidor continua o mesmo trace.
 *
 * <p>Métricas (ADR 0009, seção 2.8): {@code outbox.publish.latency} mede o envio de cada evento;
 * {@code outbox.pending.oldest.age.seconds} mostra o atraso do pendente mais antigo (R3).
 */
@Component
class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final SpringDataOutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionProperties.Outbox settings;
    private final Clock clock;
    private final Tracer tracer;
    private final Timer publishLatency;

    OutboxPublisher(SpringDataOutboxEventRepository repository,
                    KafkaTemplate<String, String> kafka,
                    TransactionProperties properties,
                    Clock clock,
                    Tracer tracer,
                    MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafka = kafka;
        this.settings = properties.outbox();
        this.clock = clock;
        this.tracer = tracer;
        this.publishLatency = Timer.builder("outbox.publish.latency")
                .description("Tempo de envio de um evento do outbox ao ack do Kafka")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        Gauge.builder("outbox.pending.oldest.age.seconds", this, OutboxPublisher::oldestPendingAgeSeconds)
                .description("Idade do registro pendente mais antigo do outbox (R3)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval-ms}")
    @Transactional
    public int publishPending() {
        List<OutboxEventJpaEntity> batch = repository.lockPending(settings.batchSize());
        int published = 0;
        for (OutboxEventJpaEntity event : batch) {
            try {
                send(event);
                event.markPublished(clock.instant());
                published++;
            } catch (Exception e) {
                log.warn("Falha ao publicar evento {}; será reenviado com o mesmo eventId", event.getEventId(), e);
                break;
            }
        }
        return published;
    }

    private void send(OutboxEventJpaEntity event) throws Exception {
        Span span = startSpanFrom(event.getTraceParent());
        long start = System.nanoTime();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafka.send(settings.topic(), event.getPartitionKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            publishLatency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } finally {
            span.end();
        }
    }

    /** Idade, em segundos, do pendente mais antigo; zero quando não há pendentes. */
    private double oldestPendingAgeSeconds() {
        Instant oldest = repository.findOldestPendingCreatedAt();
        if (oldest == null) {
            return 0;
        }
        return Duration.between(oldest, clock.instant()).toMillis() / 1000.0;
    }

    /** Span filho do contexto gravado no evento; sem contexto, cria um span novo. */
    private Span startSpanFrom(String traceParent) {
        Span.Builder builder = tracer.spanBuilder().name("outbox.publish");
        TraceContext parent = parse(traceParent);
        if (parent != null) {
            builder.setParent(parent);
        }
        return builder.start();
    }

    /** Lê {@code 00-<traceId>-<spanId>-<flags>} (W3C). Formato inválido vira ausência de contexto. */
    private TraceContext parse(String traceParent) {
        if (traceParent == null) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4) {
            return null;
        }
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled(Boolean.TRUE)
                .build();
    }
}
