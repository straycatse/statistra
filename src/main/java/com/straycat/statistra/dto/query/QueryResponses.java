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

    /**
     * @param uniqueActors distinct people, counting an identified user once
     *                     however many devices or sessions they used, and
     *                     ignoring events with no actor such as webhooks and
     *                     cron runs. This is the "how many people" number, as
     *                     opposed to totalEvents.
     */
    public record Summary(
            Instant from,
            Instant to,
            long totalEvents,
            long uniqueActors,
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

    /**
     * One step of a funnel.
     *
     * @param actors            distinct people who reached this step in order.
     *                          An actor is counted once however many times they
     *                          repeated the event.
     * @param conversionFromPrevious share of the previous step's actors who went
     *                          on to reach this one. 1.0 for the first step.
     * @param conversionFromFirst share of the funnel's entrants who got this far.
     * @param medianSecondsFromPrevious typical time to take this step, or null
     *                          for the first step and whenever nobody converted.
     */
    public record FunnelStep(
            int step,
            String eventType,
            long actors,
            double conversionFromPrevious,
            double conversionFromFirst,
            Double medianSecondsFromPrevious) {
    }

    /**
     * @param conversionWindow the bound each actor had to finish in, from their
     *                         first step
     * @param overallConversion share of entrants who completed every step
     */
    public record Funnel(
            Instant from,
            Instant to,
            String conversionWindow,
            long entered,
            long completed,
            double overallConversion,
            List<FunnelStep> steps) {
    }
}
