package com.persou.antifraud.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.persou.antifraud.testsupport.SharedInfrastructure;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * O-02 e O-03: com o Kafka indisponível, o evento continua pendente e é reenviado depois,
 * sempre com o mesmo {@code eventId}. As métricas de publicação aparecem no registro.
 *
 * <p>O Kafka é simulado no {@code KafkaTemplate}: as duas primeiras tentativas falham e a terceira é aceita.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPublishingResilienceIntegrationTest {

    private static final Pattern TRANSACTION_ID = Pattern.compile("\"transactionId\":\"([^\"]+)\"");
    private static final Pattern EVENT_ID = Pattern.compile("\"eventId\":\"([^\"]+)\"");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
    }

    @Test
    void pendingEventIsRetriedWithSameEventIdAndMetricsAreRecorded() throws Exception {
        // Duas falhas de envio (broker indisponível) e depois sucesso.
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker indisponível (simulado)")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker indisponível (simulado)")))
                .thenReturn(CompletableFuture.completedFuture(null));

        String customer = "outbox-" + UUID.randomUUID();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"customerId\":\"" + customer + "\",\"amountInCents\":15000,\"currency\":\"BRL\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        Matcher matcher = TRANSACTION_ID.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        String transactionId = matcher.group(1);

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Integer published = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND published_at IS NOT NULL",
                    Integer.class, transactionId);
            assertThat(published).isEqualTo(1);
        });

        // O-03: todas as tentativas enviaram o mesmo evento, com o mesmo eventId.
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(3)).send(anyString(), anyString(), payloads.capture());
        List<String> eventIds = new ArrayList<>();
        for (String payload : payloads.getAllValues()) {
            Matcher id = EVENT_ID.matcher(payload);
            assertThat(id.find()).isTrue();
            eventIds.add(id.group(1));
        }
        assertThat(eventIds).hasSize(3).containsOnly(eventIds.get(0));

        // Métricas do outbox (ADR 0009, seção 2.8).
        assertThat(meters.get("outbox.publish.latency").timer().count()).isGreaterThan(0);
        assertThat(meters.get("outbox.pending.oldest.age.seconds").gauge()).isNotNull();
    }
}
