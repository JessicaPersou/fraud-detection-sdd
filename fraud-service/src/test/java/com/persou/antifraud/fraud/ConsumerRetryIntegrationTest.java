package com.persou.antifraud.fraud;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.persou.antifraud.fraud.application.port.out.InboxRepository;
import com.persou.antifraud.testsupport.SharedInfrastructure;

/**
 * C-04: falha transitória de banco no consumidor é retentada; falha persistente leva ao
 * dead-letter topic após as retentativas.
 * C-05: o offset só é confirmado depois que a análise termina, então a mensagem não se perde.
 */
@SpringBootTest
class ConsumerRetryIntegrationTest {

    private static final String TRANSACTIONS = "transaction-events";
    private static final String ALERTS = "fraud-alerts";
    private static final String DLT = "transaction-events.DLT";
    private static final String GROUP = "fraud-retry-" + UUID.randomUUID();

    @MockitoSpyBean
    private InboxRepository inbox;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.FRAUD_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("fraud.kafka.consumer-group", () -> GROUP);
        registry.add("fraud.rules.amount.threshold-cents", () -> "100000");
        registry.add("fraud.rules.frequency.max-transactions", () -> "5");
        registry.add("fraud.rules.frequency.window", () -> "PT60S");
    }

    @BeforeAll
    static void createTopics() {
        SharedInfrastructure.createTopics(12, TRANSACTIONS, ALERTS, DLT);
    }

    @AfterEach
    void restoreInbox() {
        reset(inbox);
    }

    @Test
    void transientDatabaseFailureIsRetriedAndOffsetIsCommittedAfterAnalysis() throws Exception {
        // Primeira chamada falha com falha transitória de banco; as seguintes funcionam.
        doThrow(new DataAccessResourceFailureException("banco indisponível (simulado)"))
                .doCallRealMethod()
                .when(inbox).exists(anyString());
        String transactionId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String customer = "retry-" + UUID.randomUUID();

        RecordMetadata sent = send(TRANSACTIONS, customer, created(eventId, transactionId, customer, 150_000));

        ConsumerRecord<String, String> alert = awaitRecord(ALERTS, customer, transactionId, 60);
        assertThat(alert.value()).contains("\"riskLevel\":\"MEDIUM\"");

        // C-05: o offset do grupo avança além da mensagem só depois que a análise terminou.
        TopicPartition partition = new TopicPartition(TRANSACTIONS, sent.partition());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(committedOffset(partition)).isGreaterThan(sent.offset()));
    }

    @Test
    void persistentDatabaseFailureEndsInDeadLetterTopicAfterRetries() throws Exception {
        doThrow(new DataAccessResourceFailureException("banco indisponível (simulado)"))
                .when(inbox).exists(anyString());
        String transactionId = UUID.randomUUID().toString();
        String customer = "dlt-" + UUID.randomUUID();

        send(TRANSACTIONS, customer, created(UUID.randomUUID().toString(), transactionId, customer, 150_000));

        ConsumerRecord<String, String> dead = awaitRecord(DLT, customer, transactionId, 90);
        assertThat(dead.value()).contains(transactionId);
        // Uma entrega original mais três retentativas.
        verify(inbox, times(4)).exists(anyString());
    }

    private static String created(String eventId, String transactionId, String customer, long amount) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TRANSACTION_CREATED\",\"eventVersion\":1,"
                + "\"transactionId\":\"" + transactionId + "\",\"customerId\":\"" + customer + "\","
                + "\"amountInCents\":" + amount + ",\"currency\":\"BRL\","
                + "\"occurredAt\":\"" + Instant.now() + "\"}";
    }

    private static RecordMetadata send(String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(topic, key, value)).get(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao enviar evento de teste", e);
        }
    }

    private static long committedOffset(TopicPartition partition) throws Exception {
        Properties props = new Properties();
        props.put("bootstrap.servers", SharedInfrastructure.bootstrapServers());
        try (Admin admin = Admin.create(props)) {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, SECONDS);
            var committed = offsets.get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, String key, String contains,
                                                              long timeoutSeconds) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "retry-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + SECONDS.toNanos(timeoutSeconds);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key()) && record.value().contains(contains)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Registro não encontrado em " + topic + " em " + timeoutSeconds + " s");
    }
}
