package com.persou.antifraud.fraud;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.testsupport.SharedInfrastructure;

/**
 * Pipeline completo do Fraud Detection com Kafka e MySQL reais.
 *
 * <p>Os limites abaixo são valores de teste, explícitos aqui. Não são os limites de negócio.
 */
@SpringBootTest
class FraudPipelineIntegrationTest {

    private static final String TRANSACTIONS = "transaction-events";
    private static final String ALERTS = "fraud-alerts";
    private static final String DLT = "transaction-events.DLT";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.FRAUD_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("fraud.rules.amount.threshold-cents", () -> "100000");
        registry.add("fraud.rules.frequency.max-transactions", () -> "5");
        registry.add("fraud.rules.frequency.window", () -> "PT60S");
        registry.add("fraud.kafka.consumer-group", () -> "fraud-it-" + UUID.randomUUID());
    }

    @BeforeAll
    static void createTopics() {
        SharedInfrastructure.createTopics(12, TRANSACTIONS, ALERTS, DLT);
    }

    @Test
    void highAmountProducesOneAlertWithExplanation() {
        String customerId = "c-" + UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();
        send(TRANSACTIONS, customerId, created(UUID.randomUUID().toString(), transactionId, customerId, 150_000));

        // O alerta carrega o transactionId, não o eventId de origem: é ele que identifica a análise.
        ConsumerRecord<String, String> alert = awaitRecord(ALERTS, customerId, transactionId, 30);

        assertThat(alert.key()).isEqualTo(customerId);
        assertThat(alert.value()).contains("\"eventType\":\"FRAUD_DETECTED\"");
        assertThat(alert.value()).contains("\"riskLevel\":\"MEDIUM\"");
        assertThat(alert.value()).contains("\"ruleCode\":\"HIGH_TRANSACTION_AMOUNT\"");
        assertThat(alert.value()).contains("\"reason\":\"");
    }

    @Test
    void lowRiskTransactionProducesNoAlert() throws InterruptedException {
        String customerId = "c-" + UUID.randomUUID();
        String lowTransactionId = UUID.randomUUID().toString();
        String markerTransactionId = UUID.randomUUID().toString();
        send(TRANSACTIONS, customerId, created(UUID.randomUUID().toString(), lowTransactionId, customerId, 1_000));
        // Transação de controle com alerta: quando ela chega ao tópico, a de baixo risco já foi processada.
        send(TRANSACTIONS, customerId, created(UUID.randomUUID().toString(), markerTransactionId, customerId, 150_000));
        awaitRecord(ALERTS, customerId, markerTransactionId, 30);

        assertThat(recordsContaining(ALERTS, customerId, lowTransactionId)).isEmpty();
    }

    @Test
    void duplicatedEventIsAnalysedOnceAndAlertedOnce() throws InterruptedException {
        String customerId = "c-" + UUID.randomUUID();
        String transactionId = UUID.randomUUID().toString();
        String payload = created(UUID.randomUUID().toString(), transactionId, customerId, 150_000);
        send(TRANSACTIONS, customerId, payload);
        send(TRANSACTIONS, customerId, payload);

        awaitRecord(ALERTS, customerId, transactionId, 30);
        Thread.sleep(2_000);

        assertThat(recordsContaining(ALERTS, customerId, transactionId)).hasSize(1);
    }

    @Test
    void invalidPayloadGoesDirectlyToDeadLetterTopic() {
        String customerId = "c-" + UUID.randomUUID();
        String marker = "bad-" + UUID.randomUUID();
        send(TRANSACTIONS, customerId, "{\"eventType\":\"NOT_A_TRANSACTION\",\"marker\":\"" + marker + "\"}");

        ConsumerRecord<String, String> dead = awaitRecord(DLT, customerId, marker, 60);

        assertThat(dead.value()).contains(marker);
    }

    private static String created(String eventId, String transactionId, String customerId, long amount) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TRANSACTION_CREATED\",\"eventVersion\":1,"
                + "\"transactionId\":\"" + transactionId + "\",\"customerId\":\"" + customerId + "\","
                + "\"amountInCents\":" + amount + ",\"currency\":\"BRL\","
                + "\"occurredAt\":\"" + Instant.now() + "\"}";
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

    private static ConsumerRecord<String, String> awaitRecord(String topic, String key, String contains,
                                                              long timeoutSeconds) {
        long deadline = System.nanoTime() + SECONDS.toNanos(timeoutSeconds);
        try (KafkaConsumer<String, String> consumer = consumer()) {
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

    private static List<ConsumerRecord<String, String>> recordsContaining(String topic, String key,
                                                                         String text) throws InterruptedException {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            long end = System.nanoTime() + SECONDS.toNanos(3);
            while (System.nanoTime() < end) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(record.key()) && record.value().contains(text)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }
}
