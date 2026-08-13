package com.straycat.statistra.service;

import com.straycat.statistra.dto.OrganizationCreatedResponse;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.repository.OrganizationRepository;
import com.straycat.statistra.security.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    /**
     * Creates an organization and mints its first key.
     *
     * <p>The returned plaintext key is the only time it is ever available. It is
     * not stored and cannot be recovered, only replaced via
     * {@link #rotateKey(Long)}.
     */
    @Transactional
    public OrganizationCreatedResponse create(String name) {
        String apiKey = ApiKeys.generate();
        Organization organization = new Organization(
                name, ApiKeys.hash(apiKey), ApiKeys.displayPrefix(apiKey));
        Organization saved = organizationRepository.save(organization);

        log.info("Created organization {} (id={})", name, saved.getId());
        return new OrganizationCreatedResponse(
                saved.getId(), saved.getName(), apiKey, saved.getCreatedAt());
    }

    @Transactional
    public OrganizationCreatedResponse rotateKey(Long organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No organization with id " + organizationId));

        String apiKey = ApiKeys.generate();
        organization.setApiKeyHash(ApiKeys.hash(apiKey));
        organization.setApiKeyPrefix(ApiKeys.displayPrefix(apiKey));

        log.info("Rotated API key for organization id={}", organizationId);
        return new OrganizationCreatedResponse(
                organization.getId(), organization.getName(), apiKey, organization.getCreatedAt());
    }
}
