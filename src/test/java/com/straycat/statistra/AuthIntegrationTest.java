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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Authentication, admin provisioning, and tenant isolation.
 */
@IntegrationTest
class AuthIntegrationTest {

    private static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearEvents() {
        jdbcTemplate.update("DELETE FROM analytics_events");
    }

    @Test
    void ingestRequiresAnApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange("/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(Map.of("eventType", "x"), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "unauthorized");
    }

    @Test
    void unknownApiKeysAreRejected() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "x"), "st_not_a_real_key"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void queryEndpointsAlsoRequireAKey() {
        ResponseEntity<Map> response = rest.exchange("/api/v1/analytics/summary",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminApiRequiresTheAdminToken() {
        ResponseEntity<Map> noToken = rest.exchange("/admin/organizations", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A tenant key must not grant operator access.
        HttpHeaders tenantHeaders = new HttpHeaders();
        tenantHeaders.setBearerAuth(DevOrgSeeder.DEV_API_KEY);
        ResponseEntity<Map> tenantKey = rest.exchange("/admin/organizations", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders), Map.class);
        assertThat(tenantKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCanProvisionAnOrganizationAndTheIssuedKeyWorks() {
        ResponseEntity<Map> created = rest.exchange("/admin/organizations", HttpMethod.POST,
                adminEntity(Map.of("name", "Acme AB")), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String apiKey = (String) created.getBody().get("apiKey");
        assertThat(apiKey).startsWith("st_");

        ResponseEntity<Map> accepted = rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "page_view"), apiKey), Map.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void listingOrganizationsNeverExposesKeyMaterial() {
        rest.exchange("/admin/organizations", HttpMethod.POST,
                adminEntity(Map.of("name", "Secretive Ltd")), Map.class);

        ResponseEntity<List> response = rest.exchange("/admin/organizations", HttpMethod.GET,
                adminEntity(null), List.class);

        List<Map<String, Object>> organizations = response.getBody();
        assertThat(organizations).isNotEmpty();
        assertThat(organizations).allSatisfy(org -> {
            assertThat(org).doesNotContainKey("apiKey");
            assertThat(org).doesNotContainKey("apiKeyHash");
            assertThat(org).containsKey("apiKeyPrefix");
        });
    }

    @Test
    void rotatingAKeyInvalidatesThePrevousOne() {
        ResponseEntity<Map> created = rest.exchange("/admin/organizations", HttpMethod.POST,
                adminEntity(Map.of("name", "Rotating Co")), Map.class);
        Long id = ((Number) created.getBody().get("id")).longValue();
        String originalKey = (String) created.getBody().get("apiKey");

        // Use the key first, so its lookup is cached. Without this the test has
        // no teeth: an uncached key is re-read from the database on the next
        // request and would appear revoked even if eviction were broken.
        assertThat(rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "before_rotation"), originalKey), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<Map> rotated = rest.exchange("/admin/organizations/" + id + "/rotate-key",
                HttpMethod.POST, adminEntity(null), Map.class);
        String newKey = (String) rotated.getBody().get("apiKey");

        assertThat(newKey).isNotEqualTo(originalKey);
        assertThat(rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "x"), originalKey), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "x"), newKey), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void oneTenantCannotSeeAnotherTenantsEvents() {
        String tenantA = provisionOrganization("Tenant A");
        String tenantB = provisionOrganization("Tenant B");

        rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "secret_of_a"), tenantA), Map.class);

        // Wait on tenant A's own view rather than a global row count. Events
        // published by earlier tests can still be in flight and land after the
        // per-test cleanup, which would make a table-wide assertion flaky.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<Map> summary = rest.exchange("/api/v1/analytics/summary",
                    HttpMethod.GET, entity(null, tenantA), Map.class);
            assertThat(((Number) summary.getBody().get("totalEvents")).longValue()).isEqualTo(1L);
        });

        // The row exists and B is authenticated, but scoping happens in the
        // predicate rather than in a caller-supplied parameter, so B sees nothing.
        ResponseEntity<Map> bSees = rest.exchange("/api/v1/analytics/summary",
                HttpMethod.GET, entity(null, tenantB), Map.class);
        assertThat(((Number) bSees.getBody().get("totalEvents")).longValue()).isZero();

        ResponseEntity<Map> bEvents = rest.exchange("/api/v1/events",
                HttpMethod.GET, entity(null, tenantB), Map.class);
        assertThat((List<?>) bEvents.getBody().get("events")).isEmpty();
    }

    @Test
    void oversizedBodiesAreRejected() {
        // Comfortably past the 1 MB default ceiling.
        String large = "x".repeat(1_200_000);
        ResponseEntity<Map> response = rest.exchange("/api/v1/events", HttpMethod.POST,
                entity(Map.of("eventType", "page_view", "metadata", Map.of("blob", large)),
                        DevOrgSeeder.DEV_API_KEY), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("error", "payload_too_large");
    }

    private String provisionOrganization(String name) {
        ResponseEntity<Map> created = rest.exchange("/admin/organizations", HttpMethod.POST,
                adminEntity(Map.of("name", name)), Map.class);
        return (String) created.getBody().get("apiKey");
    }

    private HttpEntity<Object> entity(Object body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", apiKey);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Object> adminEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(ADMIN_TOKEN);
        return new HttpEntity<>(body, headers);
    }
}
