package com.persou.antifraud.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.testsupport.SharedInfrastructure;

/**
 * Testes de integração do {@code POST /transactions} com MySQL e Kafka reais (Testcontainers).
 * Cobre a API, a idempotência (ADR 0004) e a publicação do outbox (ADR 0003).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionApiIntegrationTest {

    private static final Pattern TRANSACTION_ID = Pattern.compile("\"transactionId\":\"([^\"]+)\"");
    private static final String TOPIC = "transaction-events";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
    }

    @BeforeAll
    static void createTopic() {
        SharedInfrastructure.createTopics(12, TOPIC);
    }

    @Test
    void createsTransactionOnceAndReplaysSameResponseForSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = body("replay-" + key, 150_000, "BRL");

        HttpResponse<String> first = post(key, body);
        HttpResponse<String> second = post(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(transactionId(first.body())).isNotBlank();
    }

    @Test
    void publishesTransactionCreatedWithCustomerIdAsKey() throws Exception {
        String customerId = "customer-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = post(key, body(customerId, 150_000, "BRL"));
        assertThat(response.statusCode()).isEqualTo(201);
        String transactionId = transactionId(response.body());

        ConsumerRecord<String, String> record = awaitRecord(customerId, transactionId);

        assertThat(record.key()).isEqualTo(customerId);
        assertThat(record.value()).contains("\"eventType\":\"TRANSACTION_CREATED\"");
        assertThat(record.value()).contains("\"eventId\":\"");
        assertThat(record.value()).contains("\"transactionId\":\"" + transactionId + "\"");
    }

    @Test
    void sameKeyWithDifferentPayloadReturns422() throws Exception {
        String key = UUID.randomUUID().toString();
        assertThat(post(key, body("conflict-" + key, 150_000, "BRL")).statusCode()).isEqualTo(201);

        HttpResponse<String> response = post(key, body("conflict-" + key, 150_001, "BRL"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).doesNotContain("SQL", "transaction_record", "Exception");
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body("no-key", 150_000, "BRL")))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void invalidBodyReturns400WithoutInternalDetails() throws Exception {
        HttpResponse<String> response = post(UUID.randomUUID().toString(),
                "{\"customerId\":\"\",\"amountInCents\":-1,\"currency\":\"BRL\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("Exception", "SQL");
    }

    @Test
    void unsupportedCurrencyReturns422() throws Exception {
        HttpResponse<String> response = post(UUID.randomUUID().toString(), body("usd", 150_000, "USD"));

        assertThat(response.statusCode()).isEqualTo(422);
    }

    private HttpResponse<String> post(String key, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri() {
        return URI.create("http://localhost:" + port + "/transactions");
    }

    private static String body(String customerId, long amountInCents, String currency) {
        return "{\"customerId\":\"" + customerId + "\",\"amountInCents\":" + amountInCents
                + ",\"currency\":\"" + currency + "\"}";
    }

    private static String transactionId(String json) {
        Matcher matcher = TRANSACTION_ID.matcher(json);
        assertThat(matcher.find()).as("transactionId presente na resposta").isTrue();
        return matcher.group(1);
    }

    /** Lê o tópico até encontrar o evento da transação, com timeout. */
    private static ConsumerRecord<String, String> awaitRecord(String customerId, String transactionId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        long deadline = System.nanoTime() + SECONDS.toNanos(30);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (customerId.equals(record.key()) && record.value().contains(transactionId)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Evento TRANSACTION_CREATED não publicado em 30 s");
    }
}
