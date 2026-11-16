package com.persou.antifraud.fraud.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.persou.antifraud.contracts.TransactionCreatedEvent;
import com.persou.antifraud.fraud.application.port.in.AnalyzeTransactionUseCase;
import com.persou.antifraud.testsupport.SharedInfrastructure;

/**
 * Spike test da regra BR-01 em MySQL (Fase 3b, risco R1).
 *
 * <p>Mede a latência de {@link AnalyzeTransactionUseCase#analyze} sob concorrência, sem Kafka,
 * isolando o custo do caminho de frequência (inbox, histórico, contagem e persistência).
 *
 * <p>Não roda no build padrão. Execução: {@code mvn -pl fraud-service -Dgroups=spike test}.
 * Parâmetros: {@code -Dspike.events}, {@code -Dspike.customers}, {@code -Dspike.threads}.
 *
 * <p>Atenção: resultados em máquina de desenvolvimento não validam a meta de 25.000 TPS nem o
 * SLA de 500 ms. Essa validação exige ambiente representativo (ADR 0008, seção 2.6).
 */
@Tag("spike")
@SpringBootTest
class FrequencyRuleSpikeTest {

    @Autowired
    private AnalyzeTransactionUseCase analyze;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedInfrastructure.jdbcUrl(SharedInfrastructure.FRAUD_DB));
        registry.add("spring.datasource.username", SharedInfrastructure::mysqlUser);
        registry.add("spring.datasource.password", SharedInfrastructure::mysqlPassword);
        registry.add("spring.kafka.bootstrap-servers", SharedInfrastructure::bootstrapServers);
        // Valores de teste de carga, não de negócio.
        registry.add("fraud.rules.amount.threshold-cents", () -> "100000");
        registry.add("fraud.rules.frequency.max-transactions", () -> "5");
        registry.add("fraud.rules.frequency.window", () -> "PT60S");
    }

    @Test
    void measuresFrequencyAnalysisLatencyUnderConcurrency() throws Exception {
        int events = Integer.getInteger("spike.events", 20_000);
        int customers = Integer.getInteger("spike.customers", 1_000);
        int threads = Integer.getInteger("spike.threads", 16);

        // Eventos de um mesmo cliente são processados em sequência na produção (mesma partição).
        // Por isso cada thread cuida de um subconjunto de clientes, sem concorrência por cliente.
        List<Long> nanos = new ArrayList<>(events);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            int perThread = events / threads;
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                futures.add(pool.submit(() -> runSlice(threadIndex, perThread, customers / threads)));
            }
            for (Future<List<Long>> f : futures) {
                nanos.addAll(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("SPIKE BR-01: eventos=%d clientes=%d threads=%d p50=%.2f ms p95=%.2f ms p99=%.2f ms max=%.2f ms%n",
                sorted.length, customers, threads,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted[sorted.length - 1] / 1e6);

        assertThat(sorted).hasSize(events / threads * threads);
    }

    private List<Long> runSlice(int threadIndex, int events, int customersPerThread) {
        List<Long> timings = new ArrayList<>(events);
        Instant base = Instant.now();
        for (int i = 0; i < events; i++) {
            String customerId = "spike-" + threadIndex + "-" + (i % customersPerThread);
            String eventId = UUID.randomUUID().toString();
            TransactionCreatedEvent event = TransactionCreatedEvent.create(eventId, "t-" + eventId, customerId,
                    1_000, "BRL", base.plusMillis(i));
            long start = System.nanoTime();
            analyze.analyze(event);
            timings.add(System.nanoTime() - start);
        }
        return timings;
    }

    private static double percentile(long[] sortedNanos, double p) {
        int index = (int) Math.ceil(p * sortedNanos.length) - 1;
        return sortedNanos[Math.max(index, 0)] / 1e6;
    }
}
