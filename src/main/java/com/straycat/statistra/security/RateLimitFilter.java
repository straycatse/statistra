package com.straycat.statistra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.config.StatistraProperties;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-organization request ceiling on the ingest path.
 *
 * <p>A fixed window token bucket held in memory. Two limitations worth stating
 * rather than discovering later:
 *
 * <ul>
 *   <li>The count is <strong>per instance</strong>. Running two replicas allows
 *       twice the configured rate. A shared counter in Redis is the fix when
 *       that matters.</li>
 *   <li>It is a fixed window, so a client can burst across a window boundary at
 *       up to twice the rate.</li>
 * </ul>
 *
 * <p>Both are acceptable for an abuse-prevention floor, which is what this is.
 * It is not a billing meter.
 */
@Component
@Order(Ordered.RATE_LIMIT)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000L;

    private final StatistraProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(StatistraProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Writes only. Read queries are comparatively cheap and rate-limiting
        // them would penalise dashboards polling their own data.
        return !request.getRequestURI().startsWith("/api/v1/events")
                || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Object attribute = request.getAttribute(ApiKeyAuthFilter.ORGANIZATION_ATTRIBUTE);
        if (!(attribute instanceof Organization organization)) {
            // Unauthenticated; the auth filter will reject it.
            filterChain.doFilter(request, response);
            return;
        }

        int limit = properties.getIngest().getRateLimitPerMinute();
        if (!allow(organization.getId(), limit)) {
            log.warn("Rate limit of {}/min exceeded by organization {}", limit, organization.getId());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "error", "rate_limited",
                    "message", "Exceeded " + limit + " requests per minute",
                    "timestamp", Instant.now().toString()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean allow(Long organizationId, int limit) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(organizationId, (id, existing) -> {
            if (existing == null || now - existing.startedAt >= WINDOW_MILLIS) {
                return new Window(now);
            }
            return existing;
        });
        return window.count.incrementAndGet() <= limit;
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicLong count = new AtomicLong();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
