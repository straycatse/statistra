package com.straycat.statistra.config;

import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.repository.OrganizationRepository;
import com.straycat.statistra.security.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Inserts a fixed development organization so a fresh checkout can send events
 * without first calling the admin API.
 *
 * <p>Gated behind {@code statistra.seed-dev-org}, which is only set in the local
 * and test profiles. The key below is a published constant and is worthless as a
 * credential, which is exactly why it must never be enabled in production.
 *
 * <p>This is a guarded component rather than a Flyway migration because Flyway
 * runs the same migrations regardless of active profile. Seeding here keeps the
 * migration history identical across environments.
 */
@Configuration
@ConditionalOnProperty(name = "statistra.seed-dev-org", havingValue = "true")
public class DevOrgSeeder {

    public static final String DEV_API_KEY = "st_dev_local_key_not_for_production";
    private static final String DEV_ORG_NAME = "Local Development";
    private static final Logger log = LoggerFactory.getLogger(DevOrgSeeder.class);

    @Bean
    public ApplicationRunner seedDevOrganization(OrganizationRepository repository) {
        return args -> {
            String hash = ApiKeys.hash(DEV_API_KEY);
            if (repository.findByApiKeyHash(hash).isPresent()) {
                log.info("Development organization already present");
                return;
            }
            Organization organization = new Organization(
                    DEV_ORG_NAME, hash, ApiKeys.displayPrefix(DEV_API_KEY));
            repository.save(organization);

            log.warn("""
                    Seeded development organization '{}'.
                    API key: {}
                    This key is a public constant. Never enable statistra.seed-dev-org outside local development.""",
                    DEV_ORG_NAME, DEV_API_KEY);
        };
    }
}
