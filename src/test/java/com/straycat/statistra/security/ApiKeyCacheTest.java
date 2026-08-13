package com.straycat.statistra.security;

import com.straycat.statistra.config.StatistraProperties;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.repository.OrganizationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cache exists to keep Postgres off the ingest path, so what matters is
 * that it actually stops issuing reads, and that it never keeps a revoked key
 * alive longer than intended.
 */
class ApiKeyCacheTest {

    private static final String HASH = "abc123";

    private final OrganizationRepository repository = mock(OrganizationRepository.class);

    private ApiKeyCache cacheWith(Duration ttl) {
        StatistraProperties properties = new StatistraProperties();
        properties.getAuth().setCacheTtl(ttl);
        return new ApiKeyCache(repository, properties, new SimpleMeterRegistry());
    }

    @Test
    void readsTheDatabaseOnceThenServesFromMemory() {
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.of(org()));
        ApiKeyCache cache = cacheWith(Duration.ofMinutes(1));

        for (int i = 0; i < 50; i++) {
            assertThat(cache.lookup(HASH)).isPresent();
        }

        // The whole point: 50 authenticated requests, one database read.
        verify(repository, times(1)).findByApiKeyHash(HASH);
    }

    @Test
    void cachesMissesSoInvalidKeysCannotHammerTheDatabase() {
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.empty());
        ApiKeyCache cache = cacheWith(Duration.ofMinutes(1));

        for (int i = 0; i < 50; i++) {
            assertThat(cache.lookup(HASH)).isEmpty();
        }

        verify(repository, times(1)).findByApiKeyHash(HASH);
    }

    @Test
    void evictionMakesARevokedKeyStopWorkingAtOnce() {
        when(repository.findByApiKeyHash(HASH)).thenReturn(Optional.of(org()));
        ApiKeyCache cache = cacheWith(Duration.ofMinutes(1));
        assertThat(cache.lookup(HASH)).isPresent();

        // Rotation happened: the row no longer carries this hash.
        cache.evict(HASH);
        when(repository.findByApiKeyHash(HASH)).thenReturn(Optional.empty());

        // Must not wait out the TTL. A revoked credential staying valid for a
        // minute is the failure mode this test exists to prevent.
        assertThat(cache.lookup(HASH)).isEmpty();
        verify(repository, times(2)).findByApiKeyHash(HASH);
    }

    @Test
    void aZeroTtlDisablesCachingEntirely() {
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.of(org()));
        ApiKeyCache cache = cacheWith(Duration.ZERO);

        cache.lookup(HASH);
        cache.lookup(HASH);
        cache.lookup(HASH);

        // The escape hatch has to actually work, or an operator who disables
        // caching to get strict revocation would silently still be caching.
        verify(repository, times(3)).findByApiKeyHash(HASH);
    }

    private Organization org() {
        return new Organization("Acme", HASH, "st_abc");
    }
}
