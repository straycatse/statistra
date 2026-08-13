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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Funnel semantics.
 *
 * <p>Events are inserted straight into Postgres rather than pushed through
 * Kafka, because these tests are about the query and need exact control of
 * timestamps and identity. The ingest path for identity is covered separately
 * by {@link #identitySurvivesTheWholeIngestPipeline()}.
 *
 * <p>Each test targets a specific way funnels are got wrong, rather than just
 * asserting a happy path.
 */
@IntegrationTest
class FunnelIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final String FROM = "2026-04-01T00:00:00Z";
    private static final String TO = "2026-06-01T00:00:00Z";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM analytics_events");
        // Look the org up by the hash of the key the tests authenticate with,
        // rather than by a guessed display prefix. Exact, and it cannot drift if
        // the prefix length ever changes.
        orgId = jdbcTemplate.queryForObject(
                "SELECT id FROM organizations WHERE api_key_hash = ? ORDER BY id LIMIT 1",
                Long.class, com.straycat.statistra.security.ApiKeys.hash(DevOrgSeeder.DEV_API_KEY));
    }

    @Test
    void countsActorsWhoCompletedEachStepInOrder() {
        // Three actors enter, two sign up, one buys.
        event("a", "page_view", T0);
        event("a", "signup", T0.plus(1, ChronoUnit.HOURS));
        event("a", "purchase", T0.plus(2, ChronoUnit.HOURS));
        event("b", "page_view", T0);
        event("b", "signup", T0.plus(1, ChronoUnit.HOURS));
        event("c", "page_view", T0);

        Map body = funnel("page_view", "signup", "purchase");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");

        assertThat(actors(steps, 0)).isEqualTo(3L);
        assertThat(actors(steps, 1)).isEqualTo(2L);
        assertThat(actors(steps, 2)).isEqualTo(1L);
        assertThat((Double) steps.get(1).get("conversionFromPrevious")).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        assertThat((Double) steps.get(2).get("conversionFromFirst")).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        assertThat(((Number) body.get("completed")).longValue()).isEqualTo(1L);
    }

    @Test
    void aStepTakenBeforeThePreviousOneIsNotAConversion() {
        // The failure mode that makes a funnel worthless: counting each event
        // type independently would report this actor as fully converted, when
        // they actually purchased and only later saw the page.
        event("a", "purchase", T0);
        event("a", "page_view", T0.plus(1, ChronoUnit.HOURS));
        event("a", "signup", T0.plus(2, ChronoUnit.HOURS));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) funnel("page_view", "signup", "purchase").get("steps");

        assertThat(actors(steps, 0)).isEqualTo(1L);
        assertThat(actors(steps, 1)).isEqualTo(1L);
        assertThat(actors(steps, 2)).isZero();
    }

    @Test
    void repeatingAStepDoesNotLetAnActorCountTwice() {
        event("a", "page_view", T0);
        event("a", "page_view", T0.plus(5, ChronoUnit.MINUTES));
        event("a", "page_view", T0.plus(10, ChronoUnit.MINUTES));
        event("a", "signup", T0.plus(20, ChronoUnit.MINUTES));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) funnel("page_view", "signup").get("steps");

        assertThat(actors(steps, 0)).isEqualTo(1L);
        assertThat(actors(steps, 1)).isEqualTo(1L);
    }

    @Test
    void conversionsOutsideTheWindowDoNotCount() {
        event("slow", "page_view", T0);
        event("slow", "signup", T0.plus(10, ChronoUnit.DAYS));
        event("fast", "page_view", T0);
        event("fast", "signup", T0.plus(1, ChronoUnit.DAYS));

        List<Map<String, Object>> within =
                (List<Map<String, Object>>) funnel("7d", "page_view", "signup").get("steps");
        assertThat(actors(within, 1)).isEqualTo(1L);

        List<Map<String, Object>> wider =
                (List<Map<String, Object>>) funnel("30d", "page_view", "signup").get("steps");
        assertThat(actors(wider, 1)).isEqualTo(2L);
    }

    @Test
    void theWindowIsMeasuredFromEntryNotFromThePreviousStep() {
        // Each hop is inside a 3d window, but the journey takes 6 days end to
        // end. Measuring the window per-hop instead of from entry would silently
        // allow N times the window on an N-step funnel.
        event("a", "page_view", T0);
        event("a", "signup", T0.plus(2, ChronoUnit.DAYS));
        event("a", "purchase", T0.plus(4, ChronoUnit.DAYS));

        List<Map<String, Object>> steps = (List<Map<String, Object>>)
                funnel("3d", "page_view", "signup", "purchase").get("steps");

        assertThat(actors(steps, 1)).isEqualTo(1L);
        assertThat(actors(steps, 2)).isZero();
    }

    @Test
    void anonymousHistoryIsStitchedToTheUserTheyBecame() {
        // The funnel most people actually want, and the one that breaks without
        // identity resolution: the visitor and the account are the same person.
        insert("anon-1", null, "page_view", T0);
        insert("anon-1", "user-9", "signup", T0.plus(1, ChronoUnit.HOURS));
        insert("anon-1", "user-9", "purchase", T0.plus(2, ChronoUnit.HOURS));

        List<Map<String, Object>> steps = (List<Map<String, Object>>)
                funnel("page_view", "signup", "purchase").get("steps");

        assertThat(actors(steps, 0)).isEqualTo(1L);
        assertThat(actors(steps, 2)).isEqualTo(1L);
    }

    @Test
    void reportsMedianTimeBetweenSteps() {
        event("a", "page_view", T0);
        event("a", "signup", T0.plus(10, ChronoUnit.MINUTES));
        event("b", "page_view", T0);
        event("b", "signup", T0.plus(30, ChronoUnit.MINUTES));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) funnel("page_view", "signup").get("steps");

        assertThat(steps.get(0).get("medianSecondsFromPrevious")).isNull();
        assertThat(((Number) steps.get(1).get("medianSecondsFromPrevious")).doubleValue())
                .isEqualTo(1200.0);   // midpoint of 10 and 30 minutes
    }

    @Test
    void eventsWithNoActorAreIgnoredRatherThanMergedIntoOne() {
        // Webhooks and cron runs have neither identifier. Treating them as a
        // single phantom actor would invent conversions that never happened.
        insert(null, null, "page_view", T0);
        insert(null, null, "signup", T0.plus(1, ChronoUnit.HOURS));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) funnel("page_view", "signup").get("steps");

        assertThat(actors(steps, 0)).isZero();
    }

    @Test
    void oneTenantsFunnelCannotSeeAnotherTenantsActors() {
        long other = jdbcTemplate.queryForObject(
                "INSERT INTO organizations (name, api_key_hash, api_key_prefix, created_at) "
                        + "VALUES ('Other', 'other-hash', 'st_other', now()) RETURNING id",
                Long.class);
        insertFor(other, "a", null, "page_view", T0);
        insertFor(other, "a", null, "signup", T0.plus(1, ChronoUnit.HOURS));

        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) funnel("page_view", "signup").get("steps");

        assertThat(actors(steps, 0)).isZero();
    }

    @Test
    void aFunnelNeedsAtLeastTwoSteps() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/analytics/funnel?step=page_view&from=" + FROM + "&to=" + TO,
                HttpMethod.GET, authorised(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    void anUnparseableWindowIsRejected() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/analytics/funnel?step=a&step=b&window=fortnight", HttpMethod.GET,
                authorised(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void identitySurvivesTheWholeIngestPipeline() {
        // The only test here that goes through HTTP and Kafka, confirming the
        // identity a client sends is what lands in the column.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", DevOrgSeeder.DEV_API_KEY);
        rest.exchange("/api/v1/events", HttpMethod.POST, new HttpEntity<>(Map.of(
                "eventType", "signup",
                "userId", "auth0|abc123",
                "anonymousId", "anon-77"), headers), Map.class);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM analytics_events WHERE user_id = ?",
                        Long.class, "auth0|abc123")).isEqualTo(1L));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT user_id, anonymous_id, actor_id FROM analytics_events WHERE user_id = ?",
                "auth0|abc123");

        assertThat(row).containsEntry("anonymous_id", "anon-77");
        // A non-UUID identifier has to survive verbatim: typing this column as
        // UUID would have excluded every Auth0 or Firebase subject.
        assertThat(row).containsEntry("user_id", "auth0|abc123");
        // user_id wins, so an identified actor is stable across devices.
        assertThat(row).containsEntry("actor_id", "auth0|abc123");
    }

    // ---- helpers ----------------------------------------------------------

    private Map funnel(String... stepsOrWindow) {
        String window = stepsOrWindow[0].matches("\\d+[mhd]") ? stepsOrWindow[0] : null;
        int start = window == null ? 0 : 1;
        StringBuilder url = new StringBuilder("/api/v1/analytics/funnel?from=" + FROM + "&to=" + TO);
        if (window != null) {
            url.append("&window=").append(window);
        }
        for (int i = start; i < stepsOrWindow.length; i++) {
            url.append("&step=").append(stepsOrWindow[i]);
        }
        ResponseEntity<Map> response = rest.exchange(url.toString(), HttpMethod.GET,
                authorised(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long actors(List<Map<String, Object>> steps, int index) {
        return ((Number) steps.get(index).get("actors")).longValue();
    }

    private void event(String anonymousId, String type, Instant at) {
        insert(anonymousId, null, type, at);
    }

    private void insert(String anonymousId, String userId, String type, Instant at) {
        insertFor(orgId, anonymousId, userId, type, at);
    }

    private void insertFor(long organizationId, String anonymousId, String userId,
                           String type, Instant at) {
        jdbcTemplate.update("""
                INSERT INTO analytics_events
                    (event_id, organization_id, event_type, occurred_at, metadata,
                     user_id, anonymous_id)
                VALUES (?, ?, ?, ?, '{}'::jsonb, ?, ?)
                """, UUID.randomUUID(), organizationId, type,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), userId, anonymousId);
    }

    private HttpEntity<Void> authorised() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", DevOrgSeeder.DEV_API_KEY);
        return new HttpEntity<>(headers);
    }
}
