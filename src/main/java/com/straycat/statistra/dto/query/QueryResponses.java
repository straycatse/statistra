package com.straycat.statistra.dto.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes for the query API. */
public final class QueryResponses {

    private QueryResponses() {
    }

    /** One bucket of a time series. Buckets with no events report zero. */
    public record TimeSeriesPoint(Instant bucket, long count) {
    }

    public record TimeSeries(
            Instant from,
            Instant to,
            String interval,
            long total,
            List<TimeSeriesPoint> points) {
    }

    /**
     * One group of a breakdown. {@code value} is null when grouping by a
     * metadata key that some events do not carry.
     */
    public record BreakdownEntry(String value, long count) {
    }

    public record Breakdown(
            Instant from,
            Instant to,
            String groupBy,
            long total,
            List<BreakdownEntry> entries) {
    }

    public record Summary(
            Instant from,
            Instant to,
            long totalEvents,
            long distinctEventTypes,
            Instant firstEventAt,
            Instant lastEventAt) {
    }

    public record EventView(
            UUID eventId,
            String eventType,
            Instant occurredAt,
            Instant receivedAt,
            Map<String, Object> metadata) {
    }

    /**
     * A page of events.
     *
     * @param nextOffset offset to pass to fetch the following page, or null when
     *                   this is the last page
     */
    public record EventPage(
            List<EventView> events,
            int limit,
            long offset,
            Long nextOffset) {
    }

    public record EventTypeEntry(String eventType, long count, Instant lastSeenAt) {
    }
}
