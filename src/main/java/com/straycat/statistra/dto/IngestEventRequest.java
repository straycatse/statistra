package com.straycat.statistra.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event as submitted by a client.
 *
 * <p>Note what is absent: there is no {@code organizationId}. Tenancy comes from
 * the API key, so accepting one here would let any caller write into another
 * tenant's data.
 *
 * @param eventId    optional client-supplied idempotency key. Resending the same
 *                   id is a no-op, which makes client retries safe. Generated
 *                   server-side when omitted.
 * @param occurredAt when the event happened, defaulting to now. Clients may
 *                   backfill history by setting this to a past instant.
 */
public record IngestEventRequest(
        UUID eventId,

        @NotBlank(message = "eventType is required")
        @Size(max = 200, message = "eventType must be at most 200 characters")
        String eventType,

        Instant occurredAt,

        Map<String, Object> metadata) {

    /** Metadata is optional over the wire; absent is stored as an empty object. */
    public Map<String, Object> metadataOrEmpty() {
        return metadata == null ? Map.of() : metadata;
    }

    public UUID eventIdOrRandom() {
        return eventId == null ? UUID.randomUUID() : eventId;
    }

    public Instant occurredAtOrNow() {
        return occurredAt == null ? Instant.now() : occurredAt;
    }
}
