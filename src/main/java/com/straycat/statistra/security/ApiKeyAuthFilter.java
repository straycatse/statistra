package com.straycat.statistra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.entity.Organization;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@code X-API-Key} header to an organization for every
 * {@code /api/**} request.
 *
 * <p>Centralising this is the point: previously each handler repeated the lookup
 * inline, so any new endpoint could silently ship unauthenticated.
 */
@Component
@Order(Ordered.API_KEY_AUTH)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String ORGANIZATION_ATTRIBUTE = "statistra.organization";
    private static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final ApiKeyCache apiKeyCache;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(ApiKeyCache apiKeyCache, ObjectMapper objectMapper) {
        this.apiKeyCache = apiKeyCache;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the tenant API is key-protected. /admin has its own token filter,
        // and /actuator is left to the platform.
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            unauthorized(response, "Missing " + HEADER + " header");
            return;
        }

        // Goes through the cache rather than straight to the repository. A
        // per-request database read here meant a Postgres outage took ingest
        // down with it, even though Kafka was healthy and could have buffered
        // every event. See ApiKeyCache for the staleness trade that buys.
        Optional<Organization> organization = apiKeyCache.lookup(ApiKeys.hash(apiKey.trim()));

        if (organization.isEmpty()) {
            // Log the prefix only. Logging the key itself would put a live
            // credential into log storage.
            log.warn("Rejected request to {} with unknown API key (prefix {})",
                    request.getRequestURI(), ApiKeys.displayPrefix(apiKey.trim()));
            unauthorized(response, "Invalid API key");
            return;
        }

        request.setAttribute(ORGANIZATION_ATTRIBUTE, organization.get());
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "unauthorized",
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}
