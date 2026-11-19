package com.persou.antifraud.notification;

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
class NotificationServiceSmokeTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.NOTIFICATION_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
        registry.add("notification.channel", () -> "EMAIL");
        registry.add("notification.recipient", () -> "ops-test@example.invalid");
        // O provedor é "simulated" neste teste (o perfil local define smtp, ADR 0011); não há SMTP (Mailpit)
        // no ambiente de teste, então o health check de e-mail não pode derrubar /actuator/health.
        registry.add("notification.provider", () -> "simulated");
        registry.add("management.health.mail.enabled", () -> "false");
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
