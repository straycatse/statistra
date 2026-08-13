package com.straycat.statistra;

import com.straycat.statistra.config.DevOrgSeeder;
import com.straycat.statistra.support.IntegrationTest;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end coverage of the pipeline: HTTP ingest, through Kafka, into
 * Postgres, back out through the query API.
 */
@IntegrationTest
class IngestAndQueryIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearEvents() {
        jdbcTemplate.update("DELETE FROM analytics_events");
    }

    @Test
    void ingestedEventBecomesQueryable() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minus(1, ChronoUnit.HOURS);

        ResponseEntity<Map> accepted = post("/api/v1/events", Map.of(
                "eventId", eventId.toString(),
                "eventType", "page_view",
                "occurredAt", occurredAt.toString(),
                "metadata", Map.of("plan", "pro", "country", "SE")));

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).containsEntry("accepted", 1);

        awaitEventCount(1);

        ResponseEntity<Map> series = get("/api/v1/analytics/timeseries?interval=hour");
        assertThat(series.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) series.getBody().get("total")).longValue()).isEqualTo(1L);
    }

    @Test
    void resendingTheSameEventIdDoesNotDoubleCount() {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "eventId", eventId.toString(),
                "eventType", "signup",
                "metadata", Map.of());

        post("/api/v1/events", event);
        awaitEventCount(1);

        // A client retry after a timeout. Without the (organization_id, event_id)
        // constraint this would inflate every metric derived from the table.
        post("/api/v1/events", event);
        post("/api/v1/events", event);

        // Give the consumer time to actually process the redeliveries, otherwise
        // this asserts on work that has not happened yet and passes vacuously.
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo(1L));
    }

    @Test
    void batchIngestStoresEveryEvent() {
        List<Map<String, Object>> events = List.of(
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "pro")),
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "free")),
                Map.of("eventType", "signup", "metadata", Map.of("plan", "pro")));

        ResponseEntity<Map> response = post("/api/v1/events/batch", Map.of("events", events));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("accepted", 3);
        awaitEventCount(3);
    }

    @Test
    void breakdownGroupsByEventType() {
        post("/api/v1/events/batch", Map.of("events", List.of(
                Map.of("eventType", "page_view"),
                Map.of("eventType", "page_view"),
                Map.of("eventType", "signup"))));
        awaitEventCount(3);

        ResponseEntity<Map> response = get("/api/v1/analytics/breakdown?groupBy=eventType");
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("value", "page_view");
        assertThat(((Number) entries.get(0).get("count")).longValue()).isEqualTo(2L);
    }

    @Test
    void breakdownGroupsByMetadataKey() {
        post("/api/v1/events/batch", Map.of("events", List.of(
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "pro")),
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "pro")),
                Map.of("eventType", "signup", "metadata", Map.of("plan", "free")))));
        awaitEventCount(3);

        ResponseEntity<Map> response = get("/api/v1/analytics/breakdown?groupBy=metadata.plan");
        List<Map<String, Object>> entries = (List<Map<String, Object>>) response.getBody().get("entries");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("value", "pro");
        assertThat(((Number) entries.get(0).get("count")).longValue()).isEqualTo(2L);
    }

    @Test
    void metadataFilterNarrowsResultsUsingContainment() {
        post("/api/v1/events/batch", Map.of("events", List.of(
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "pro", "country", "SE")),
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "free", "country", "SE")),
                Map.of("eventType", "page_view", "metadata", Map.of("plan", "pro", "country", "NO")))));
        awaitEventCount(3);

        ResponseEntity<Map> filtered = get("/api/v1/analytics/summary?filter=plan:pro");
        assertThat(((Number) filtered.getBody().get("totalEvents")).longValue()).isEqualTo(2L);

        ResponseEntity<Map> both = get("/api/v1/analytics/summary?filter=plan:pro&filter=country:SE");
        assertThat(((Number) both.getBody().get("totalEvents")).longValue()).isEqualTo(1L);
    }

    @Test
    void timeSeriesReportsEmptyBucketsAsZero() {
        post("/api/v1/events", Map.of(
                "eventType", "page_view",
                "occurredAt", Instant.now().toString()));
        awaitEventCount(1);

        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(6, ChronoUnit.HOURS);

        ResponseEntity<Map> response = get(
                "/api/v1/analytics/timeseries?interval=hour&from=" + from + "&to=" + to);
        List<Map<String, Object>> points = (List<Map<String, Object>>) response.getBody().get("points");

        // Without the generate_series gap fill this would return a single point
        // and a chart drawn from it would misrepresent the window.
        assertThat(points).hasSizeGreaterThanOrEqualTo(6);
        assertThat(points).anyMatch(p -> ((Number) p.get("count")).longValue() == 0L);
        assertThat(points).anyMatch(p -> ((Number) p.get("count")).longValue() == 1L);
    }

    @Test
    void dayBucketsAreUtcMidnightsRegardlessOfServerTimeZone() {
        // Bucket boundaries are the answer, not presentation, so they cannot
        // depend on where the process happens to run. date_trunc on a
        // timestamptz truncates in the session timezone, which pgjdbc sets from
        // the JVM default, so this previously returned the server's local
        // midnights: on a machine at UTC+2 a "day" ran 22:00Z to 22:00Z.
        Instant occurredAt = Instant.parse("2026-03-04T23:30:00Z");
        post("/api/v1/events", Map.of(
                "eventType", "page_view",
                "occurredAt", occurredAt.toString()));
        awaitEventCount(1);

        ResponseEntity<Map> response = get("/api/v1/analytics/timeseries?interval=day"
                + "&from=2026-03-01T00:00:00Z&to=2026-03-08T00:00:00Z");
        List<Map<String, Object>> points = (List<Map<String, Object>>) response.getBody().get("points");

        assertThat(points).extracting(p -> (String) p.get("bucket"))
                .allSatisfy(bucket -> assertThat(Instant.parse(bucket))
                        .isEqualTo(Instant.parse(bucket).truncatedTo(ChronoUnit.DAYS)));

        // Half-open range: seven day-buckets for a seven-day window. An eighth,
        // starting exactly at `to`, could never hold an event and used to be
        // emitted as a trailing zero on every chart ending at midnight.
        assertThat(points).hasSize(7);
        assertThat(points.get(0)).containsEntry("bucket", "2026-03-01T00:00:00Z");
        assertThat(points.get(6)).containsEntry("bucket", "2026-03-07T00:00:00Z");

        // 23:30Z on the 4th belongs to the 4th in UTC, not the 5th.
        assertThat(points.get(3)).containsEntry("bucket", "2026-03-04T00:00:00Z");
        assertThat(((Number) points.get(3).get("count")).longValue()).isEqualTo(1L);
    }

    @Test
    void eventTypesListsWhatHasActuallyBeenSent() {
        post("/api/v1/events/batch", Map.of("events", List.of(
                Map.of("eventType", "page_view"),
                Map.of("eventType", "user_signup"),
                // The typo case this endpoint exists to make visible.
                Map.of("eventType", "user_signedup"))));
        awaitEventCount(3);

        ResponseEntity<List> response = rest.exchange(
                "/api/v1/event-types", HttpMethod.GET, authorised(null), List.class);

        List<Map<String, Object>> types = response.getBody();
        assertThat(types).extracting(t -> t.get("eventType"))
                .containsExactlyInAnyOrder("page_view", "user_signup", "user_signedup");
    }

    @Test
    void rawEventListPaginates() {
        List<Map<String, Object>> events = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> Map.<String, Object>of("eventType", "page_view"))
                .toList();
        post("/api/v1/events/batch", Map.of("events", events));
        awaitEventCount(5);

        ResponseEntity<Map> firstPage = get("/api/v1/events?limit=2");
        assertThat((List<?>) firstPage.getBody().get("events")).hasSize(2);
        assertThat(firstPage.getBody().get("nextOffset")).isEqualTo(2);

        ResponseEntity<Map> lastPage = get("/api/v1/events?limit=2&offset=4");
        assertThat((List<?>) lastPage.getBody().get("events")).hasSize(1);
        assertThat(lastPage.getBody().get("nextOffset")).isNull();
    }

    @Test
    void invalidEventIsRejectedWithFieldDetail() {
        ResponseEntity<Map> response = post("/api/v1/events", Map.of("metadata", Map.of("a", "b")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "validation_failed");
        assertThat((Map<String, String>) response.getBody().get("fields")).containsKey("eventType");
    }

    @Test
    void malformedQueryParametersAreRejected() {
        assertThat(get("/api/v1/analytics/timeseries?interval=century").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/breakdown?groupBy=metadata.bad key").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/analytics/summary?filter=novalue").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void awaitEventCount(long expected) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(countEvents()).isEqualTo(expected));
    }

    private Long countEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM analytics_events", Long.class);
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, authorised(body), Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return rest.exchange(path, HttpMethod.GET, authorised(null), Map.class);
    }

    private HttpEntity<Object> authorised(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", DevOrgSeeder.DEV_API_KEY);
        return new HttpEntity<>(body, headers);
    }
}
