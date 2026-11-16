package com.persou.antifraud.testsupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers compartilhados por toda a JVM de testes (um MySQL e um Kafka).
 *
 * <p>Os containers sobem uma vez e são encerrados pelo Ryuk do Testcontainers. Cada serviço usa
 * um banco próprio, como em {@code infrastructure/docker/mysql/init/01-databases.sql}.
 * O usuário de teste tem acesso a todos os bancos de teste; é uma simplificação restrita a testes.
 */
public final class SharedInfrastructure {

    public static final String TRANSACTION_DB = "transaction_db";
    public static final String FRAUD_DB = "fraud_db";
    public static final String NOTIFICATION_DB = "notification_db";

    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName(TRANSACTION_DB)
            .withUsername("test")
            .withPassword("test");

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        MYSQL.start();
        KAFKA.start();
        createDatabases();
    }

    /**
     * Cria os bancos dos serviços como root. O usuário de teste só recebe acesso a eles.
     * Feito por comando no container: um script de init roda com o usuário de teste, que não cria bancos.
     */
    private static void createDatabases() {
        String sql = "CREATE DATABASE IF NOT EXISTS " + FRAUD_DB + " CHARACTER SET utf8mb4; "
                + "CREATE DATABASE IF NOT EXISTS " + NOTIFICATION_DB + " CHARACTER SET utf8mb4; "
                + "GRANT ALL PRIVILEGES ON *.* TO 'test'@'%'; FLUSH PRIVILEGES;";
        try {
            var result = MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e", sql);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("Falha ao criar bancos de teste: " + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criar bancos de teste", e);
        }
    }

    private SharedInfrastructure() {
    }

    public static String jdbcUrl(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?allowPublicKeyRetrieval=true&useSSL=false";
    }

    public static String mysqlUser() {
        return MYSQL.getUsername();
    }

    public static String mysqlPassword() {
        return MYSQL.getPassword();
    }

    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /**
     * Cria os tópicos com o número de partições do ADR 0005, se ainda não existirem.
     */
    public static void createTopics(int partitions, String... topics) {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            List<NewTopic> newTopics = java.util.Arrays.stream(topics)
                    .map(name -> new NewTopic(name, partitions, (short) 1))
                    .toList();
            admin.createTopics(newTopics).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Tópicos já existentes não são erro para os testes.
            if (!String.valueOf(e.getCause()).contains("TopicExistsException")) {
                throw new IllegalStateException("Falha ao criar tópicos de teste", e);
            }
        }
    }
}
