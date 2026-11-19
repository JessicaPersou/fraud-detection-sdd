package com.persou.antifraud.notification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.notification.adapter.out.provider.SimulatedNotificationProvider;
import com.persou.antifraud.testsupport.SharedInfrastructure;

/**
 * Notification Service com Kafka e MySQL reais: aceite, reentrega, retry e DLQ.
 */
@SpringBootTest
class NotificationPipelineIntegrationTest {

    private static final String ALERTS = "fraud-alerts";
    private static final String DLT = "fraud-alerts.DLT";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SimulatedNotificationProvider provider;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.NOTIFICATION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("notification.channel", () -> "EMAIL");
        registry.add("notification.recipient", () -> "ops-test@example.invalid");
        // Fixa o provedor de teste: o perfil local (ADR 0011) define smtp, que não existe no ambiente de teste.
        registry.add("notification.provider", () -> "simulated");
        registry.add("notification.kafka.consumer-group", () -> "notif-it-" + UUID.randomUUID());
    }

    @BeforeAll
    static void createTopics() {
        SharedInfrastructure.createTopics(12, ALERTS, DLT);
    }

    @AfterEach
    void resetProvider() {
        provider.failNext(0);
    }

    @Test
    void alertIsAcceptedByProviderAndRecorded() {
        String eventId = UUID.randomUUID().toString();
        send(ALERTS, "c-" + eventId, alert(eventId));

        String status = awaitStatus(eventId, 30, "ACCEPTED_BY_PROVIDER");

        assertThat(status).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(attempts(eventId)).isEqualTo(1);
    }

    @Test
    void redeliveredAlertIsNotSentTwice() throws InterruptedException {
        String eventId = UUID.randomUUID().toString();
        send(ALERTS, "c-" + eventId, alert(eventId));
        awaitStatus(eventId, 30, "ACCEPTED_BY_PROVIDER");

        send(ALERTS, "c-" + eventId, alert(eventId));
        Thread.sleep(2_000);

        assertThat(attempts(eventId)).isEqualTo(1);
    }

    @Test
    void transientProviderFailureIsRetriedUntilAccepted() {
        String eventId = UUID.randomUUID().toString();
        provider.failNext(2);
        send(ALERTS, "c-" + eventId, alert(eventId));

        String status = awaitStatus(eventId, 60, "ACCEPTED_BY_PROVIDER");

        assertThat(status).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(attempts(eventId)).isEqualTo(3);
    }

    @Test
    void persistentProviderFailureEndsInFailedStatusAndDeadLetterTopic() {
        String eventId = UUID.randomUUID().toString();
        provider.failNext(100);
        send(ALERTS, "c-" + eventId, alert(eventId));

        ConsumerRecord<String, String> dead = awaitRecord(DLT, eventId, 90);

        assertThat(dead.value()).contains(eventId);
        assertThat(awaitStatus(eventId, 5, "FAILED")).isEqualTo("FAILED");
    }

    @Test
    void invalidAlertGoesDirectlyToDeadLetterTopic() {
        String marker = "bad-" + UUID.randomUUID();
        send(ALERTS, "c-" + marker, "{\"eventType\":\"WRONG\",\"marker\":\"" + marker + "\"}");

        ConsumerRecord<String, String> dead = awaitRecord(DLT, marker, 60);

        assertThat(dead.value()).contains(marker);
    }

    private static String alert(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"FRAUD_DETECTED\",\"eventVersion\":1,"
                + "\"transactionId\":\"" + UUID.randomUUID() + "\",\"customerId\":\"c-" + eventId + "\","
                + "\"riskLevel\":\"MEDIUM\",\"triggeredRules\":[{\"ruleCode\":\"HIGH_TRANSACTION_AMOUNT\","
                + "\"description\":\"d\",\"reason\":\"r\"}],\"analyzedAt\":\"2026-11-15T13:00:01Z\"}";
    }

    private String awaitStatus(String eventId, long timeoutSeconds, String target) {
        long deadline = System.nanoTime() + SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM notification_record WHERE event_id = ?", String.class, eventId);
            if (!statuses.isEmpty() && statuses.get(0).equals(target)) {
                return statuses.get(0);
            }
            sleep(200);
        }
        return jdbc.queryForList("SELECT status FROM notification_record WHERE event_id = ?",
                String.class, eventId).stream().findFirst().orElse("AUSENTE");
    }

    private int attempts(String eventId) {
        Integer value = jdbc.queryForObject(
                "SELECT attempts FROM notification_record WHERE event_id = ?", Integer.class, eventId);
        return value == null ? 0 : value;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void send(String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao enviar evento de teste", e);
        }
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, String contains, long timeoutSeconds) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notif-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + SECONDS.toNanos(timeoutSeconds);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(contains)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Registro não encontrado em " + topic);
    }
}
