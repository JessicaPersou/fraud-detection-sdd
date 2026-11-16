package com.persou.antifraud.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.testsupport.SharedInfrastructure;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionServiceSmokeTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
    }

    @Test
    void healthEndpointReportsUp() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
