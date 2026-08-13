package com.straycat.statistra;

import com.straycat.statistra.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may read the management endpoints.
 *
 * <p>Management runs on the same port the internet reaches, so this is a public
 * boundary rather than an internal one: {@code /actuator/prometheus} exposes
 * ingest volume per counter, which is commercially sensitive, and the JVM and
 * connection-pool gauges alongside it are useful reconnaissance.
 */
@IntegrationTest
class ActuatorAccessTest {

    /** Matches {@code statistra.admin.token} in application-test.properties. */
    private static final String ADMIN_TOKEN = "test-admin-token";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsOpenSoThePlatformProbeCanReachIt() {
        // railway.toml health-checks this path with no credentials. Gating it
        // would leave every deploy stuck reporting unhealthy.
        assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/health/readiness", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void metricsAreNotReadableWithoutTheAdminToken() {
        assertThat(get("/actuator/prometheus", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/actuator/metrics", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTenantApiKeyDoesNotGrantOperatorAccess() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", com.straycat.statistra.config.DevOrgSeeder.DEV_API_KEY);

        ResponseEntity<String> response = rest.exchange("/actuator/prometheus",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Asserts against {@code /actuator/metrics} rather than
     * {@code /actuator/prometheus} because Boot disables metrics <em>export</em>
     * inside {@code @SpringBootTest} unless {@code @AutoConfigureObservability}
     * is present, so the Prometheus registry, and with it its endpoint, does not
     * exist here. That is a test-context default, not a deployment difference:
     * the scrape endpoint is live in a running app. The unauthenticated case
     * above does cover {@code /actuator/prometheus} directly, since the filter
     * rejects it before routing decides whether it exists.
     */
    @Test
    void metricsAreReadableWithTheAdminToken() {
        ResponseEntity<String> response = get("/actuator/metrics", ADMIN_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Assert the payload is real, not merely that the status is 200: the
        // point of gating rather than disabling is that operators keep access.
        assertThat(response.getBody()).contains("statistra.events.persisted");
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
