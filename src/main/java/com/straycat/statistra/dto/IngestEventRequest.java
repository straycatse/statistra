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
 * @param userId     your own identifier for the person, not ours. Stored
 *                   verbatim and scoped to your organization, so it can never
 *                   collide with another tenant's. Keep it pseudonymous: an
 *                   internal id, never an email address.
 * @param anonymousId client-generated identifier that survives from before
 *                   sign-in. Keep sending it <em>after</em> the user
 *                   authenticates as well: that overlap is the only record
 *                   linking someone's anonymous history to their account, and
 *                   it cannot be reconstructed later if never written.
 */
public record IngestEventRequest(
        UUID eventId,

        @NotBlank(message = "eventType is required")
        @Size(max = 200, message = "eventType must be at most 200 characters")
        String eventType,

        Instant occurredAt,

        @Size(max = 200, message = "userId must be at most 200 characters")
        String userId,

        @Size(max = 200, message = "anonymousId must be at most 200 characters")
        String anonymousId,

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
