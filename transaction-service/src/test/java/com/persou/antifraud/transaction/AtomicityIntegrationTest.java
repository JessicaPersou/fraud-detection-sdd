package com.persou.antifraud.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.persou.antifraud.testsupport.SharedInfrastructure;
import com.persou.antifraud.transaction.application.port.out.OutboxMessage;
import com.persou.antifraud.transaction.application.port.out.OutboxRepository;

/**
 * O-01: transação e outbox são atômicos. Se o outbox falha, nada é gravado: nem a transação,
 * nem a chave de idempotência, que é liberada para o cliente repetir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AtomicityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboxRepository outbox;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
    }

    @Test
    void outboxFailureRollsBackTransactionAndReleasesKey() throws Exception {
        doThrow(new DataAccessResourceFailureException("banco indisponível (simulado)"))
                .when(outbox).save(any(OutboxMessage.class));
        String customer = "atomic-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"customerId\":\"" + customer + "\",\"amountInCents\":15000,\"currency\":\"BRL\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("transaction_record", "SQL", "Exception");
        Integer transactions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transaction_record WHERE customer_id = ?", Integer.class, customer);
        assertThat(transactions).isZero();
        Integer keys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ?", Integer.class, key);
        assertThat(keys).isZero();
    }
}
