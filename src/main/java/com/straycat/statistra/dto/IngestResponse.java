package com.straycat.statistra.dto;

import java.util.List;
import java.util.UUID;

/**
 * Returned with 202 Accepted.
 *
 * <p>202 rather than 201 is the honest status: the event has been handed to
 * Kafka but not yet persisted, so claiming the resource exists would be a lie.
 * The returned ids let a client correlate, and resending with the same id is a
 * safe retry.
 */
public record IngestResponse(int accepted, List<UUID> eventIds) {

    public static IngestResponse single(UUID eventId) {
        return new IngestResponse(1, List.of(eventId));
    }
}
