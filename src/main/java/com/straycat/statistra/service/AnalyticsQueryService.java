package com.straycat.statistra.service;

import com.straycat.statistra.dao.AnalyticsQueryDao;
import com.straycat.statistra.dto.query.BreakdownDimension;
import com.straycat.statistra.dto.query.MetadataFilter;
import com.straycat.statistra.dto.query.QueryResponses.Breakdown;
import com.straycat.statistra.dto.query.QueryResponses.BreakdownEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventPage;
import com.straycat.statistra.dto.query.QueryResponses.EventTypeEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventView;
import com.straycat.statistra.dto.query.QueryResponses.Summary;
import com.straycat.statistra.dto.query.QueryResponses.TimeSeries;
import com.straycat.statistra.dto.query.QueryResponses.TimeSeriesPoint;
import com.straycat.statistra.dto.query.TimeInterval;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsQueryService {

    /** Window used when a caller supplies neither bound. */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_BREAKDOWN_ENTRIES = 500;

    private final AnalyticsQueryDao dao;

    public AnalyticsQueryService(AnalyticsQueryDao dao) {
        this.dao = dao;
    }

    @Transactional(readOnly = true)
    public TimeSeries timeSeries(long organizationId,
                                 Instant from,
                                 Instant to,
                                 String interval,
                                 String eventType,
                                 List<String> filters) {

        Range range = Range.of(from, to);
        TimeInterval bucket = TimeInterval.from(interval);
        Map<String, String> metadataFilter = MetadataFilter.parse(filters);

        List<TimeSeriesPoint> points = dao.timeSeries(
                organizationId, range.from(), range.to(), bucket, eventType, metadataFilter);

        long total = points.stream().mapToLong(TimeSeriesPoint::count).sum();
        return new TimeSeries(range.from(), range.to(),
                bucket.name().toLowerCase(java.util.Locale.ROOT), total, points);
    }

    @Transactional(readOnly = true)
    public Breakdown breakdown(long organizationId,
                               Instant from,
                               Instant to,
                               String groupBy,
                               String eventType,
                               List<String> filters,
                               Integer limit) {

        Range range = Range.of(from, to);
        BreakdownDimension dimension = BreakdownDimension.from(groupBy);
        Map<String, String> metadataFilter = MetadataFilter.parse(filters);
        int effectiveLimit = clamp(limit == null ? 50 : limit, 1, MAX_BREAKDOWN_ENTRIES);

        List<BreakdownEntry> entries = dao.breakdown(organizationId, range.from(), range.to(),
                dimension, eventType, metadataFilter, effectiveLimit);

        long total = entries.stream().mapToLong(BreakdownEntry::count).sum();
        return new Breakdown(range.from(), range.to(),
                groupBy == null || groupBy.isBlank() ? "eventType" : groupBy, total, entries);
    }

    @Transactional(readOnly = true)
    public Summary summary(long organizationId,
                           Instant from,
                           Instant to,
                           String eventType,
                           List<String> filters) {

        Range range = Range.of(from, to);
        return dao.summary(organizationId, range.from(), range.to(),
                eventType, MetadataFilter.parse(filters));
    }

    @Transactional(readOnly = true)
    public EventPage events(long organizationId,
                            Instant from,
                            Instant to,
                            String eventType,
                            List<String> filters,
                            Integer limit,
                            Long offset) {

        Range range = Range.of(from, to);
        int effectiveLimit = clamp(limit == null ? 100 : limit, 1, MAX_PAGE_SIZE);
        long effectiveOffset = offset == null || offset < 0 ? 0 : offset;

        List<EventView> rows = dao.events(organizationId, range.from(), range.to(),
                eventType, MetadataFilter.parse(filters), effectiveLimit, effectiveOffset);

        // The DAO fetched one extra row purely to answer "is there more?".
        boolean hasMore = rows.size() > effectiveLimit;
        List<EventView> page = hasMore ? rows.subList(0, effectiveLimit) : rows;

        return new EventPage(page, effectiveLimit, effectiveOffset,
                hasMore ? effectiveOffset + effectiveLimit : null);
    }

    @Transactional(readOnly = true)
    public List<EventTypeEntry> eventTypes(long organizationId) {
        return dao.eventTypes(organizationId);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * A validated time window. Defaults to the last {@link #DEFAULT_WINDOW} so an
     * unparameterised query cannot accidentally scan the whole table.
     */
    private record Range(Instant from, Instant to) {

        static Range of(Instant from, Instant to) {
            Instant resolvedTo = to == null ? Instant.now() : to;
            Instant resolvedFrom = from == null ? resolvedTo.minus(DEFAULT_WINDOW) : from;

            if (!resolvedFrom.isBefore(resolvedTo)) {
                throw new IllegalArgumentException("'from' must be strictly before 'to'");
            }
            return new Range(resolvedFrom, resolvedTo);
        }
    }
}
