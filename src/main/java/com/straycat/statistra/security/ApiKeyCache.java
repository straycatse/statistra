package com.straycat.statistra.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.straycat.statistra.config.StatistraProperties;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.repository.OrganizationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves an API key hash to an organization, with a short-lived cache.
 *
 * <h2>Why this exists</h2>
 * Authentication used to issue a Postgres {@code SELECT} on every single request.
 * That put the database on the critical path of ingest, which is precisely the
 * path Kafka is there to keep off it: a load test with Postgres stopped saw
 * <em>every</em> ingest request fail with a 500, despite the broker being
 * healthy and perfectly able to accept the events. The queue was fine; the gate
 * in front of it was not.
 *
 * <p>With a cache, a key that has been seen recently authenticates without
 * touching the database, so ingest keeps accepting and buffering through a
 * database outage. A key that has <em>not</em> been seen still needs a lookup and
 * will still fail while the database is down. That is the honest limit of this:
 * it protects established traffic, not first contact.
 *
 * <h2>The staleness trade</h2>
 * A cached entry outlives a key rotation by up to the TTL, so a revoked key
 * keeps working for that long. {@link #evict(String)} makes rotation immediate
 * on the instance that performed it; other instances wait out the TTL. That is
 * why the default is short and why the value is configurable rather than
 * hardcoded. Set it to zero to disable caching entirely.
 *
 * <p>Misses are cached too, so a flood of invalid keys cannot be used to hammer
 * the database. The cache is size-bounded, so that caching cannot itself be
 * turned into a memory-exhaustion vector.
 */
@Component
public class ApiKeyCache {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCache.class);

    private final OrganizationRepository organizationRepository;
    private final Cache<String, Optional<Organization>> cache;
    private final boolean enabled;

    public ApiKeyCache(OrganizationRepository organizationRepository,
                       StatistraProperties properties,
                       MeterRegistry meterRegistry) {
        this.organizationRepository = organizationRepository;

        Duration ttl = properties.getAuth().getCacheTtl();
        this.enabled = !ttl.isZero() && !ttl.isNegative();
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getAuth().getCacheMaxEntries())
                .expireAfterWrite(ttl.isNegative() ? Duration.ZERO : ttl)
                .recordStats()
                .build();

        Gauge.builder("statistra.auth.cache.size", cache, Cache::estimatedSize)
                .description("Cached API key lookups")
                .register(meterRegistry);

        log.info("API key cache {} (ttl={}, maxEntries={})",
                enabled ? "enabled" : "disabled",
                ttl, properties.getAuth().getCacheMaxEntries());
    }

    /**
     * @param apiKeyHash the SHA-256 hash of the presented key, never the key itself
     * @return the owning organization, or empty if the key is unknown
     */
    public Optional<Organization> lookup(String apiKeyHash) {
        if (!enabled) {
            return organizationRepository.findByApiKeyHash(apiKeyHash);
        }
        return cache.get(apiKeyHash, organizationRepository::findByApiKeyHash);
    }

    /** Drops a cached entry so a rotated or revoked key stops working at once. */
    public void evict(String apiKeyHash) {
        cache.invalidate(apiKeyHash);
    }

    /** Test seam and operational escape hatch. */
    public void clear() {
        cache.invalidateAll();
    }
}
