package com.straycat.statistra.dto;

import com.straycat.statistra.entity.Organization;

import java.time.Instant;

/** Organization view without any credential material. */
public record OrganizationResponse(
        Long id,
        String name,
        String apiKeyPrefix,
        Instant createdAt) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getApiKeyPrefix(),
                organization.getCreatedAt());
    }
}
