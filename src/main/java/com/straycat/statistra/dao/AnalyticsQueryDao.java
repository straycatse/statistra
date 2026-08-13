package com.straycat.statistra.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.dto.query.BreakdownDimension;
import com.straycat.statistra.dto.query.QueryResponses.BreakdownEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventTypeEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventView;
import com.straycat.statistra.dto.query.QueryResponses.Summary;
import com.straycat.statistra.dto.query.QueryResponses.TimeSeriesPoint;
import com.straycat.statistra.dto.query.TimeInterval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read path for {@code analytics_events}.
 *
 * <p>Every query here is aggregate-shaped, returning buckets and counts rather
 * than entities, which is the case an ORM has nothing to offer.
 *
 * <h2>Injection safety</h2>
 * No caller-supplied value is ever concatenated into SQL. That includes the
 * parts that look like they would have to be:
 * <ul>
 *   <li>the {@code date_trunc} unit is bound, since its first argument is text;</li>
 *   <li>the {@code generate_series} stride is bound and cast with {@code ?::interval};</li>
 *   <li>metadata keys are bound through {@code metadata ->> ?};</li>
 *   <li>metadata filters are bound as one {@code ?::jsonb} containment operand.</li>
 * </ul>
 * The only text assembled in Java is the fixed set of {@code AND} clauses below,
 * none of which embed a value.
 */
@Repository
public class AnalyticsQueryDao {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsQueryDao(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Counts per bucket, with empty buckets included as zero.
     *
     * <p>The gap fill matters: a plain {@code GROUP BY} omits periods with no
     * events entirely, and a chart drawn from that silently closes the gaps and
     * misrepresents the shape of the data.
     */
    public List<TimeSeriesPoint> timeSeries(long organizationId,
                                            Instant from,
                                            Instant to,
                                            TimeInterval interval,
                                            String eventType,
                                            Map<String, String> metadataFilter) {

        Where where = where(organizationId, from, to, eventType, metadataFilter);

        // Every truncation is done in UTC via `AT TIME ZONE 'UTC'`, which turns a
        // timestamptz into the plain timestamp of its UTC wall clock. Two
        // problems make this necessary rather than stylistic:
        //
        //  * date_trunc on a timestamptz truncates in the *session* timezone,
        //    and pgjdbc sets that from the JVM default. Day buckets were
        //    therefore whatever "midnight" meant on the machine running the app,
        //    so the same query returned different buckets on a developer laptop
        //    and on a UTC server. Bucket boundaries are the meaning of the
        //    answer here, so that is a wrong result, not a formatting quirk.
        //  * Adding `interval '1 day'` to a timestamptz is calendar arithmetic
        //    in that same session zone, so a step across a DST change is 23 or
        //    25 hours and drifts out of alignment with the truncated buckets.
        //    On a plain timestamp the step is always exactly one day.
        //
        // The upper bound subtracts a microsecond because the range is
        // half-open: with `to` landing exactly on a boundary, truncating it
        // yields a bucket that starts at `to` and so can never hold an event,
        // producing a spurious trailing zero on every chart whose range ends at
        // midnight. The literal is fixed SQL text, not a caller value.
        String sql = """
                WITH buckets AS (
                    SELECT generate_series(
                        date_trunc(?, ?::timestamptz AT TIME ZONE 'UTC'),
                        date_trunc(?, (?::timestamptz - interval '1 microsecond') AT TIME ZONE 'UTC'),
                        ?::interval
                    ) AS bucket
                ),
                counts AS (
                    SELECT date_trunc(?, occurred_at AT TIME ZONE 'UTC') AS bucket, count(*) AS total
                    FROM analytics_events
                    WHERE %s
                    GROUP BY 1
                )
                SELECT (b.bucket AT TIME ZONE 'UTC') AS bucket, COALESCE(c.total, 0) AS total
                FROM buckets b
                LEFT JOIN counts c ON c.bucket = b.bucket
                ORDER BY b.bucket
                """.formatted(where.clause());

        List<Object> params = new ArrayList<>();
        params.add(interval.truncField());
        params.add(utc(from));
        params.add(interval.truncField());
        params.add(utc(to));
        params.add(interval.step());
        params.add(interval.truncField());
        params.addAll(where.params());

        return jdbcTemplate.query(sql, (rs, rowNum) -> new TimeSeriesPoint(
                instant(rs, "bucket"),
                rs.getLong("total")), params.toArray());
    }

    /** Top {@code limit} groups by count, descending. */
    public List<BreakdownEntry> breakdown(long organizationId,
                                          Instant from,
                                          Instant to,
                                          BreakdownDimension dimension,
                                          String eventType,
                                          Map<String, String> metadataFilter,
                                          int limit) {

        Where where = where(organizationId, from, to, eventType, metadataFilter);

        List<Object> params = new ArrayList<>();
        String selectExpression;
        if (dimension.type() == BreakdownDimension.Type.METADATA) {
            // The key is a bind parameter, not interpolated text.
            selectExpression = "metadata ->> ?";
            params.add(dimension.metadataKey());
        } else {
            selectExpression = "event_type";
        }

        String sql = """
                SELECT %s AS value, count(*) AS total
                FROM analytics_events
                WHERE %s
                GROUP BY 1
                ORDER BY total DESC, value ASC
                LIMIT ?
                """.formatted(selectExpression, where.clause());

        params.addAll(where.params());
        params.add(limit);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new BreakdownEntry(
                rs.getString("value"),
                rs.getLong("total")), params.toArray());
    }

    public Summary summary(long organizationId,
                           Instant from,
                           Instant to,
                           String eventType,
                           Map<String, String> metadataFilter) {

        Where where = where(organizationId, from, to, eventType, metadataFilter);

        String sql = """
                SELECT count(*)                    AS total_events,
                       count(DISTINCT event_type)  AS distinct_event_types,
                       min(occurred_at)            AS first_event_at,
                       max(occurred_at)            AS last_event_at
                FROM analytics_events
                WHERE %s
                """.formatted(where.clause());

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new Summary(
                from,
                to,
                rs.getLong("total_events"),
                rs.getLong("distinct_event_types"),
                instant(rs, "first_event_at"),
                instant(rs, "last_event_at")), where.params().toArray());
    }

    /**
     * A page of raw events, newest first.
     *
     * <p>Fetches one row beyond {@code limit} so the caller can tell whether a
     * further page exists without a second count query.
     */
    public List<EventView> events(long organizationId,
                                  Instant from,
                                  Instant to,
                                  String eventType,
                                  Map<String, String> metadataFilter,
                                  int limit,
                                  long offset) {

        Where where = where(organizationId, from, to, eventType, metadataFilter);

        String sql = """
                SELECT event_id, event_type, occurred_at, received_at, metadata
                FROM analytics_events
                WHERE %s
                ORDER BY occurred_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(where.clause());

        List<Object> params = new ArrayList<>(where.params());
        params.add(limit + 1);
        params.add(offset);

        return jdbcTemplate.query(sql, eventRowMapper(), params.toArray());
    }

    /**
     * Every event type this organization has ever sent, with counts.
     *
     * <p>Exists because event types are free-form strings, so this is what makes
     * a typo such as {@code user_signup} against {@code user_signedup} visible
     * rather than silently splitting a metric in two.
     */
    public List<EventTypeEntry> eventTypes(long organizationId) {
        String sql = """
                SELECT event_type, count(*) AS total, max(occurred_at) AS last_seen_at
                FROM analytics_events
                WHERE organization_id = ?
                GROUP BY event_type
                ORDER BY total DESC, event_type ASC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new EventTypeEntry(
                rs.getString("event_type"),
                rs.getLong("total"),
                instant(rs, "last_seen_at")), organizationId);
    }

    private RowMapper<EventView> eventRowMapper() {
        return (rs, rowNum) -> new EventView(
                UUID.fromString(rs.getString("event_id")),
                rs.getString("event_type"),
                instant(rs, "occurred_at"),
                instant(rs, "received_at"),
                readMetadata(rs.getString("metadata")));
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            // The column is jsonb, so Postgres already validated the syntax.
            // Reaching here means a document that is valid JSON but not an
            // object, which the write path cannot produce. That is corruption
            // rather than bad input, so it surfaces as a 500 rather than being
            // quietly swallowed into an empty map.
            throw new IllegalStateException("Unreadable metadata document", e);
        }
    }

    /**
     * Builds the shared predicate.
     *
     * <p>{@code organization_id} is always the first condition and is never
     * optional, which is what makes cross-tenant reads impossible even if a
     * caller omits every other filter.
     */
    private Where where(long organizationId,
                        Instant from,
                        Instant to,
                        String eventType,
                        Map<String, String> metadataFilter) {

        StringBuilder clause = new StringBuilder("organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);

        // Half-open interval: inclusive lower bound, exclusive upper, so
        // adjacent ranges neither overlap nor drop the boundary instant.
        clause.append(" AND occurred_at >= ?");
        params.add(utc(from));
        clause.append(" AND occurred_at < ?");
        params.add(utc(to));

        if (eventType != null && !eventType.isBlank()) {
            clause.append(" AND event_type = ?");
            params.add(eventType);
        }

        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            clause.append(" AND metadata @> ?::jsonb");
            params.add(toJson(metadataFilter));
        }

        return new Where(clause.toString(), params);
    }

    private String toJson(Map<String, String> filter) {
        try {
            return objectMapper.writeValueAsString(filter);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not build metadata filter", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record Where(String clause, List<Object> params) {
    }
}
