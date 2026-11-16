package com.persou.antifraud.e2e;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import com.persou.antifraud.fraud.FraudServiceApplication;
import com.persou.antifraud.notification.NotificationServiceApplication;
import com.persou.antifraud.notification.adapter.out.provider.SimulatedNotificationProvider;
import com.persou.antifraud.testsupport.SharedInfrastructure;
import com.persou.antifraud.transaction.TransactionServiceApplication;

import org.awaitility.Awaitility;

/**
 * Fluxo ponta a ponta (Fase 5; cenários F-01 a F-04, I-02 e N-01/N-04 de docs/cenarios-teste.md).
 *
 * <p>Os três serviços sobem na mesma JVM, cada um com seu contexto e seu banco. Os limites de
 * regra são valores de teste, não de negócio.
 *
 * <p>Esta suíte valida corretude do fluxo. Não valida a meta de 25.000 TPS nem o SLA de 500 ms:
 * isso exige ambiente representativo (ADR 0008, seção 2.6). A latência medida aqui é apenas informativa.
 */
class AntifraudEndToEndTest {

    private static final String TRANSACTIONS = "transaction-events";
    private static final String ALERTS = "fraud-alerts";
    private static final Pattern TRANSACTION_ID = Pattern.compile("\"transactionId\":\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext transactionService;
    private static ConfigurableApplicationContext fraudService;
    private static ConfigurableApplicationContext notificationService;
    private static int transactionPort;

    @BeforeAll
    static void startServices() {
        SharedInfrastructure.createTopics(12, TRANSACTIONS, ALERTS, TRANSACTIONS + ".DLT", ALERTS + ".DLT");
        String group = UUID.randomUUID().toString();

        transactionService = run(TransactionServiceApplication.class, "transaction-service", Map.of(
                "server.port", "0",
                "spring.datasource.url", SharedInfrastructure.jdbcUrl(SharedInfrastructure.TRANSACTION_DB)));

        fraudService = run(FraudServiceApplication.class, "fraud-service", Map.of(
                "server.port", "0",
                "spring.datasource.url", SharedInfrastructure.jdbcUrl(SharedInfrastructure.FRAUD_DB),
                "spring.kafka.consumer.auto-offset-reset", "earliest",
                "fraud.kafka.consumer-group", "fraud-e2e-" + group,
                "fraud.rules.amount.threshold-cents", "100000",
                "fraud.rules.frequency.max-transactions", "5",
                "fraud.rules.frequency.window", "PT60S"));

        notificationService = run(NotificationServiceApplication.class, "notification-service", Map.of(
                "server.port", "0",
                "spring.datasource.url", SharedInfrastructure.jdbcUrl(SharedInfrastructure.NOTIFICATION_DB),
                "spring.kafka.consumer.auto-offset-reset", "earliest",
                "notification.kafka.consumer-group", "notification-e2e-" + group,
                "notification.channel", "EMAIL",
                "notification.recipient", "ops-test@example.invalid"));

        transactionPort = Integer.parseInt(transactionService.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopServices() {
        close(notificationService);
        close(fraudService);
        close(transactionService);
    }

    @Test
    void lowValueTransactionIsAnalysedAsLowAndNotAlerted() throws Exception {
        String customer = customer();
        String transactionId = transactionId(post(customer, 1_000));

        String risk = awaitValue("SELECT risk_level FROM fraud_analysis WHERE transaction_id = ?",
                SharedInfrastructure.FRAUD_DB, transactionId);

        assertThat(risk).isEqualTo("LOW");
        assertThat(count("SELECT COUNT(*) FROM notification_record WHERE transaction_id = ?",
                SharedInfrastructure.NOTIFICATION_DB, transactionId)).isZero();
    }

    @Test
    void highValueTransactionProducesAlertAcceptedByProvider() throws Exception {
        String customer = customer();
        String transactionId = transactionId(post(customer, 150_000));

        String status = awaitValue("SELECT status FROM notification_record WHERE transaction_id = ?",
                SharedInfrastructure.NOTIFICATION_DB, transactionId);

        assertThat(status).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(awaitValue("SELECT risk_level FROM fraud_analysis WHERE transaction_id = ?",
                SharedInfrastructure.FRAUD_DB, transactionId)).isEqualTo("MEDIUM");
    }

    @Test
    void frequencyPlusAmountEscalatesToHighRisk() throws Exception {
        String customer = customer();
        for (int i = 0; i < 6; i++) {
            post(customer, 1_000);
        }
        String transactionId = transactionId(post(customer, 150_000));

        String risk = awaitValue("SELECT risk_level FROM fraud_analysis WHERE transaction_id = ?",
                SharedInfrastructure.FRAUD_DB, transactionId);
        String rules = awaitValue("SELECT triggered_rules FROM fraud_analysis WHERE transaction_id = ?",
                SharedInfrastructure.FRAUD_DB, transactionId);

        assertThat(risk).isEqualTo("HIGH");
        assertThat(rules).contains("TRANSACTION_FREQUENCY", "HIGH_TRANSACTION_AMOUNT");
    }

    @Test
    void duplicatedTransactionEventIsNotAnalysedOrNotifiedTwice() throws Exception {
        String customer = customer();
        String transactionId = transactionId(post(customer, 150_000));
        awaitValue("SELECT status FROM notification_record WHERE transaction_id = ?",
                SharedInfrastructure.NOTIFICATION_DB, transactionId);

        String payload = awaitValue("SELECT payload FROM outbox_event WHERE aggregate_id = ?",
                SharedInfrastructure.TRANSACTION_DB, transactionId);
        resend(customer, payload);
        Thread.sleep(3_000);

        assertThat(count("SELECT COUNT(*) FROM fraud_analysis WHERE transaction_id = ?",
                SharedInfrastructure.FRAUD_DB, transactionId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification_record WHERE transaction_id = ?",
                SharedInfrastructure.NOTIFICATION_DB, transactionId)).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyReplaysWithoutNewTransaction() throws Exception {
        String customer = customer();
        String key = UUID.randomUUID().toString();
        String body = body(customer, 1_000);

        HttpResponse<String> first = post(key, body);
        HttpResponse<String> second = post(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(count("SELECT COUNT(*) FROM transaction_record WHERE customer_id = ?",
                SharedInfrastructure.TRANSACTION_DB, customer)).isEqualTo(1);
    }

    @Test
    void providerUnavailabilityIsRetriedUntilAccepted() throws Exception {
        SimulatedNotificationProvider provider = notificationService.getBean(SimulatedNotificationProvider.class);
        provider.failNext(2);
        try {
            String transactionId = transactionId(post(customer(), 150_000));

            // Espera o estado final: FAILED aparece entre as tentativas e não é o resultado esperado.
            String status = awaitValueEquals("SELECT status FROM notification_record WHERE transaction_id = ?",
                    SharedInfrastructure.NOTIFICATION_DB, transactionId, "ACCEPTED_BY_PROVIDER");
            int attempts = Integer.parseInt(awaitValue(
                    "SELECT attempts FROM notification_record WHERE transaction_id = ?",
                    SharedInfrastructure.NOTIFICATION_DB, transactionId));

            assertThat(status).isEqualTo("ACCEPTED_BY_PROVIDER");
            assertThat(attempts).isEqualTo(3);
        } finally {
            provider.failNext(0);
        }
    }

    /**
     * N-02: indisponibilidade do provedor de notificação não interrompe a análise nem o registro do alerta.
     */
    @Test
    void notificationProviderOutageDoesNotBlockFraudAnalysisOrAlertRecording() throws Exception {
        SimulatedNotificationProvider provider = notificationService.getBean(SimulatedNotificationProvider.class);
        provider.failNext(100);
        try {
            String transactionId = transactionId(post(customer(), 150_000));

            assertThat(awaitValueEquals("SELECT alert_published FROM fraud_analysis WHERE transaction_id = ?",
                    SharedInfrastructure.FRAUD_DB, transactionId, "1")).isEqualTo("1");
            assertThat(count("SELECT COUNT(*) FROM notification_record WHERE transaction_id = ? "
                            + "AND status = 'ACCEPTED_BY_PROVIDER'",
                    SharedInfrastructure.NOTIFICATION_DB, transactionId)).isZero();
        } finally {
            provider.failNext(0);
        }
    }

    @Test
    void reportsEndToEndLatencyForInformation() throws Exception {
        int samples = 30;
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            String transactionId = transactionId(post(customer(), 150_000));
            awaitValue("SELECT status FROM notification_record WHERE transaction_id = ?",
                    SharedInfrastructure.NOTIFICATION_DB, transactionId);
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        millis.sort(null);
        System.out.printf("E2E INFORMATIVO (HTTP -> notificação aceita, %d amostras, ambiente de teste): "
                        + "p50=%d ms p95=%d ms max=%d ms. Não é validação do SLA de 500 ms.%n",
                samples, millis.get(samples / 2), millis.get((int) Math.ceil(samples * 0.95) - 1),
                millis.get(samples - 1));
        assertThat(millis).hasSize(samples);
    }

    // ---------- helpers ----------

    private static ConfigurableApplicationContext run(Class<?> application, String module, Map<String, String> overrides) {
        // Os três serviços têm application.yml na raiz do JAR. Na mesma JVM o Spring carregaria só um deles,
        // então cada contexto lê o próprio arquivo pelo caminho. Argumentos de linha de comando têm precedência.
        List<String> args = new ArrayList<>();
        args.add("--spring.config.location=file:../" + module + "/src/main/resources/application.yml");
        overrides.forEach((k, v) -> args.add("--" + k + "=" + v));
        // Credenciais e Kafka são comuns aos três serviços.
        args.add("--spring.datasource.username=" + SharedInfrastructure.mysqlUser());
        args.add("--spring.datasource.password=" + SharedInfrastructure.mysqlPassword());
        args.add("--spring.kafka.bootstrap-servers=" + SharedInfrastructure.bootstrapServers());
        return new SpringApplication(application).run(args.toArray(String[]::new));
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }

    private static String customer() {
        return "e2e-" + UUID.randomUUID();
    }

    /** Criação de transação nova: deve retornar 201. */
    private static HttpResponse<String> post(String customer, long amount) throws Exception {
        HttpResponse<String> response = post(UUID.randomUUID().toString(), body(customer, amount));
        assertThat(response.statusCode()).as("POST /transactions").isEqualTo(201);
        return response;
    }

    private static HttpResponse<String> post(String key, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + transactionPort + "/transactions"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String customer, long amount) {
        return "{\"customerId\":\"" + customer + "\",\"amountInCents\":" + amount + ",\"currency\":\"BRL\"}";
    }

    private static String transactionId(HttpResponse<String> response) {
        Matcher matcher = TRANSACTION_ID.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /** Reenvia o mesmo payload do outbox ao tópico, simulando reentrega do Kafka. */
    private static void resend(String customer, String payload) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedInfrastructure.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(TRANSACTIONS, customer, payload)).get(10, SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao reenviar evento", e);
        }
    }

    /** Aguarda até a consulta devolver exatamente o valor esperado, com timeout. */
    private static String awaitValueEquals(String sql, String database, String param, String expected) {
        return Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> query(sql, database, param), expected::equals);
    }

    /** Aguarda até a consulta devolver um valor, com timeout. */
    private static String awaitValue(String sql, String database, String param) {
        return Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> query(sql, database, param), value -> value != null);
    }

    private static long count(String sql, String database, String param) throws Exception {
        String value = query(sql, database, param);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String query(String sql, String database, String param) {
        try (Connection connection = DriverManager.getConnection(SharedInfrastructure.jdbcUrl(database),
                SharedInfrastructure.mysqlUser(), SharedInfrastructure.mysqlPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, param);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha na consulta de verificação", e);
        }
    }
}
