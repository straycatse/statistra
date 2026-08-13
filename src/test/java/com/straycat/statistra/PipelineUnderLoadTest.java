package com.straycat.statistra;

import com.straycat.statistra.config.DevOrgSeeder;
import com.straycat.statistra.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Correctness of the pipeline under volume.
 *
 * <h2>Why there are no timing assertions here</h2>
 * This runs on shared CI hardware, where throughput and latency vary by several
 * times between runs. Asserting "p99 under 10 ms" there produces a test that
 * fails for reasons unrelated to the code, and a suite people learn to ignore is
 * worse than no suite.
 *
 * <p>So this asserts <em>invariants</em> instead, which hold at any speed:
 * every accepted event is persisted exactly once, the counters reconcile, and
 * the consumer drains rather than falling permanently behind. Those are the
 * properties that actually break when someone weakens deduplication, drops the
 * manual acknowledgement, or reintroduces batch poisoning, and they fail
 * deterministically.
 *
 * <p>Throughput numbers belong in {@code benchmark/load_test.py}, which is run
 * deliberately against known hardware and compared over time rather than gated
 * on.
 */
@IntegrationTest
class PipelineUnderLoadTest {

    private static final int BATCHES = 10;
    private static final int PER_BATCH = 500;
    private static final int TOTAL = BATCHES * PER_BATCH;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clearEvents() {
        jdbcTemplate.update("DELETE FROM analytics_events");
    }

    @Test
    void everyAcceptedEventIsPersistedExactlyOnce() {
        for (int i = 0; i < BATCHES; i++) {
            ResponseEntity<Map> response = post(batchOf(PER_BATCH, "load_test"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        await().atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo((long) TOTAL));

        // Hold briefly and re-check. Kafka redelivers on rebalance, so a count
        // that is correct once but climbing afterwards means deduplication is
        // not actually holding.
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo((long) TOTAL));
    }

    @Test
    void redeliveringAWholeBatchAddsNothing() {
        List<Map<String, Object>> events = IntStream.range(0, 200)
                .mapToObj(i -> Map.<String, Object>of(
                        "eventId", UUID.randomUUID().toString(),
                        "eventType", "replayed"))
                .toList();

        post(Map.of("events", events));
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo(200L));

        // The same payload three more times, as a broker replay or a client
        // retry storm would deliver it.
        post(Map.of("events", events));
        post(Map.of("events", events));
        post(Map.of("events", events));

        await().pollDelay(Duration.ofSeconds(4))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo(200L));

        assertThat(counter("statistra.events.deduplicated")).isGreaterThanOrEqualTo(600.0);
    }

    @Test
    void producerAndConsumerCountsReconcile() {
        for (int i = 0; i < 4; i++) {
            post(batchOf(250, "reconcile"));
        }
        int sent = 1000;

        await().atMost(Duration.ofSeconds(90))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo((long) sent));

        double published = counter("statistra.events.published");
        double persisted = counter("statistra.events.persisted");
        double deduplicated = counter("statistra.events.deduplicated");
        double invalid = counter("statistra.events.invalid");
        double failed = counter("statistra.events.publish_failed");

        // Nothing may be silently dropped between accepting an event and
        // storing it: everything published is accounted for as stored,
        // suppressed as a duplicate, or rejected as unparseable.
        assertThat(published - failed)
                .isCloseTo(persisted + deduplicated + invalid, org.assertj.core.data.Offset.offset(1.0));
        assertThat(failed).isZero();
        assertThat(invalid).isZero();
    }

    private Map<String, Object> batchOf(int size, String type) {
        List<Map<String, Object>> events = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            events.add(Map.of("eventType", type, "metadata", Map.of("plan", "pro")));
        }
        return Map.of("events", events);
    }

    private double counter(String name) {
        return meterRegistry.find(name).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private Long countEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM analytics_events", Long.class);
    }

    private ResponseEntity<Map> post(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", DevOrgSeeder.DEV_API_KEY);
        return rest.exchange("/api/v1/events/batch", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }
}
