package com.straycat.statistra.dto;

import java.time.Instant;

/**
 * Returned when an organization is created or its key rotated.
 *
 * <p>This is the only response shape that ever carries a plaintext
 * {@code apiKey}. Listing organizations deliberately uses
 * {@link OrganizationResponse}, which cannot expose one.
 */
public record OrganizationCreatedResponse(
        Long id,
        String name,
        String apiKey,
        Instant createdAt) {
}
