package com.straycat.statistra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.config.StatistraProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * Guards the operator surfaces with a bearer token from {@code ADMIN_TOKEN}:
 * {@code /admin/**} and the non-probe Actuator endpoints.
 *
 * <p>Separate from the tenant API key deliberately: creating organizations and
 * minting keys is an operator capability, so it must not be reachable with any
 * tenant credential.
 *
 * <p>Actuator is included because the app serves management endpoints on the
 * same port the internet reaches, so {@code /actuator/prometheus} was readable
 * by anyone. It publishes {@code statistra_events_persisted_total} and
 * friends, which is the business's ingest volume, alongside JVM and pool
 * internals useful for shaping an attack. Prometheus can send a bearer token,
 * so scraping still works.
 *
 * <p>{@code /actuator/health} stays open: it is the platform's healthcheck
 * target, declared in {@code railway.toml}, and gating it would leave the
 * deployment permanently unhealthy. It reports only UP or DOWN, since health
 * detail is off by default.
 *
 * <p>When no token is configured these return 503 rather than running
 * unauthenticated. Failing closed matters here, because the failure mode of the
 * alternative is an open endpoint that issues credentials.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);
    private static final String BEARER = "Bearer ";

    private final StatistraProperties properties;
    private final ObjectMapper objectMapper;

    public AdminTokenFilter(StatistraProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/admin/")) {
            return false;
        }
        if (uri.startsWith("/actuator")) {
            // The liveness and readiness probes must answer the platform
            // unauthenticated; everything else under /actuator is operator-only.
            return uri.equals("/actuator/health") || uri.startsWith("/actuator/health/");
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String configured = properties.getAdmin().getToken();
        if (configured == null || configured.isBlank()) {
            log.error("Admin API called but ADMIN_TOKEN is not configured; refusing the request");
            respond(response, HttpStatus.SERVICE_UNAVAILABLE, "admin_disabled",
                    "Admin API is disabled because ADMIN_TOKEN is not configured");
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String presented = header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length()).trim()
                : null;

        if (!ApiKeys.secureEquals(presented, configured)) {
            log.warn("Rejected admin request to {}", request.getRequestURI());
            respond(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Invalid admin token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void respond(HttpServletResponse response, HttpStatus status, String error, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", error,
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}
