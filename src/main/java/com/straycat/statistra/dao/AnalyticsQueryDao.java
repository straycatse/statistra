package com.straycat.statistra.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.dto.query.BreakdownDimension;
import com.straycat.statistra.dto.query.FunnelSpec;
import com.straycat.statistra.dto.query.QueryResponses.BreakdownEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventTypeEntry;
import com.straycat.statistra.dto.query.QueryResponses.EventView;
import com.straycat.statistra.dto.query.QueryResponses.Funnel;
import com.straycat.statistra.dto.query.QueryResponses.FunnelStep;
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

        Where where = where(organizationId, from, to, eventType, metadataFilter, "e");

        // Resolves identity before counting people, exactly as the funnel does.
        // Counting DISTINCT actor_id directly is tempting and wrong: someone who
        // browsed anonymously and then signed up carries two different actor_ids
        // and would be reported as two people. In a ten-visitor demo where six
        // registered, that inflated "unique users" to sixteen.
        String sql = """
                WITH identity AS (
                    SELECT DISTINCT ON (anonymous_id) anonymous_id, user_id
                    FROM analytics_events
                    WHERE organization_id = ? AND occurred_at >= ? AND occurred_at < ?
                      AND anonymous_id IS NOT NULL AND user_id IS NOT NULL
                    ORDER BY anonymous_id, occurred_at
                )
                SELECT count(*)                                   AS total_events,
                       count(DISTINCT COALESCE(e.user_id, i.user_id, e.anonymous_id))
                                                                  AS unique_actors,
                       count(DISTINCT e.event_type)               AS distinct_event_types,
                       min(e.occurred_at)                         AS first_event_at,
                       max(e.occurred_at)                         AS last_event_at
                FROM analytics_events e
                LEFT JOIN identity i ON i.anonymous_id = e.anonymous_id
                WHERE %s
                """.formatted(where.clause());

        List<Object> summaryParams = new ArrayList<>();
        summaryParams.add(organizationId);
        summaryParams.add(utc(from));
        summaryParams.add(utc(to));
        summaryParams.addAll(where.params());

        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new Summary(
                from,
                to,
                rs.getLong("total_events"),
                rs.getLong("unique_actors"),
                rs.getLong("distinct_event_types"),
                instant(rs, "first_event_at"),
                instant(rs, "last_event_at")), summaryParams.toArray());
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


    /**
     * Ordered conversion funnel over distinct actors.
     *
     * <h2>How the steps are matched</h2>
     * One CTE per step, each chained to the one before it. A step only counts
     * when it happens <em>strictly after</em> the previous step for that same
     * actor, and within the conversion window measured from their first step.
     * The alternative people reach for first, counting each event type
     * independently and dividing, produces confident nonsense: someone who
     * cancelled and then signed up shows as a conversion.
     *
     * <p>{@code MIN(occurred_at)} at every step gives first-match semantics, and
     * because each CTE groups by actor, repeating a step does not let anyone
     * count twice.
     *
     * <h2>Identity resolution</h2>
     * The {@code identity} CTE derives the anonymous-to-user mapping from rows
     * that carry both, which is what a client produces when it keeps sending
     * {@code anonymousId} after login. Without it, a funnel that crosses the
     * sign-in boundary, which is most of the funnels worth measuring, would
     * break exactly where it matters: the visitor and the user they became look
     * like two different people.
     *
     * <p>The mapping is scoped to the query window, so someone who identified
     * before it is stitched only if they also appear inside it.
     *
     * <h2>Injection safety</h2>
     * The number of CTEs is structural, derived from how many steps were asked
     * for, never from their content. Every event type and the interval are bind
     * parameters.
     */
    public Funnel funnel(long organizationId,
                         Instant from,
                         Instant to,
                         FunnelSpec spec,
                         Map<String, String> metadataFilter) {

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        sql.append("""
                WITH identity AS (
                    SELECT DISTINCT ON (anonymous_id) anonymous_id, user_id
                    FROM analytics_events
                    WHERE organization_id = ? AND occurred_at >= ? AND occurred_at < ?
                      AND anonymous_id IS NOT NULL AND user_id IS NOT NULL
                    ORDER BY anonymous_id, occurred_at
                ),
                base AS (
                    SELECT e.event_type,
                           e.occurred_at,
                           COALESCE(e.user_id, i.user_id, e.anonymous_id) AS actor
                    FROM analytics_events e
                    LEFT JOIN identity i ON i.anonymous_id = e.anonymous_id
                    WHERE e.organization_id = ? AND e.occurred_at >= ? AND e.occurred_at < ?
                      AND e.actor_id IS NOT NULL
                """);
        params.add(organizationId);
        params.add(utc(from));
        params.add(utc(to));
        params.add(organizationId);
        params.add(utc(from));
        params.add(utc(to));

        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            sql.append("      AND e.metadata @> ?::jsonb\n");
            params.add(toJson(metadataFilter));
        }
        sql.append("),\n");

        String interval = spec.conversionWindow().getSeconds() + " seconds";
        List<String> steps = spec.steps();

        for (int i = 0; i < steps.size(); i++) {
            if (i == 0) {
                // t0 is carried through every later step so the window is always
                // measured from entry, not from the previous step. Otherwise a
                // funnel of N steps quietly allows N times the window.
                sql.append("""
                        s0 AS (
                            SELECT actor, MIN(occurred_at) AS t, MIN(occurred_at) AS t0
                            FROM base WHERE event_type = ? GROUP BY actor
                        )""");
                params.add(steps.get(0));
            } else {
                sql.append("""
                        , s%d AS (
                            SELECT b.actor, MIN(b.occurred_at) AS t, p.t0
                            FROM base b JOIN s%d p ON b.actor = p.actor
                            WHERE b.event_type = ?
                              AND b.occurred_at > p.t
                              AND b.occurred_at <= p.t0 + ?::interval
                            GROUP BY b.actor, p.t0
                        )""".formatted(i, i - 1));
                params.add(steps.get(i));
                params.add(interval);
            }
        }

        // One row, two columns per step: how many got here, and how long it
        // typically took them.
        sql.append("\nSELECT ");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(SELECT count(*) FROM s%d) AS c%d".formatted(i, i));
            if (i > 0) {
                // Note the parentheses around the whole concatenation:
                // .formatted binds to the last literal alone, so without them
                // only the final fragment is substituted and the rest keeps its
                // literal %d. That produced a subquery joining s1 to itself
                // while referring to an s0 that was never in the FROM clause.
                sql.append((", (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY "
                        + "EXTRACT(EPOCH FROM (cur.t - prev.t))) FROM s%d cur "
                        + "JOIN s%d prev ON prev.actor = cur.actor) AS m%d")
                        .formatted(i, i - 1, i));
            }
        }

        return jdbcTemplate.queryForObject(sql.toString(), (rs, rowNum) -> {
            List<FunnelStep> out = new ArrayList<>();
            long entered = rs.getLong("c0");
            long previous = entered;
            for (int i = 0; i < steps.size(); i++) {
                long actors = rs.getLong("c" + i);
                Double median = null;
                if (i > 0) {
                    double m = rs.getDouble("m" + i);
                    median = rs.wasNull() ? null : m;
                }
                out.add(new FunnelStep(
                        i + 1,
                        steps.get(i),
                        actors,
                        i == 0 ? 1.0 : ratio(actors, previous),
                        ratio(actors, entered),
                        median));
                previous = actors;
            }
            long completed = out.get(out.size() - 1).actors();
            return new Funnel(from, to, humanWindow(spec.conversionWindow()),
                    entered, completed, ratio(completed, entered), out);
        }, params.toArray());
    }

    /** Zero entrants is a zero conversion rate, not a division by zero. */
    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static String humanWindow(java.time.Duration d) {
        if (d.toSecondsPart() == 0 && d.toMinutesPart() == 0 && d.toHoursPart() == 0) {
            return d.toDays() + "d";
        }
        return d.toHours() % 24 == 0 && d.toMinutesPart() == 0
                ? d.toHours() + "h"
                : d.toMinutes() + "m";
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
        return where(organizationId, from, to, eventType, metadataFilter, "");
    }

    /**
     * @param alias table alias to qualify every column with, or empty when the
     *              query has only one table. Passed in rather than rewritten
     *              afterwards: a caller doing string replacement on the finished
     *              clause would also hit column names inside values.
     */
    private Where where(long organizationId,
                        Instant from,
                        Instant to,
                        String eventType,
                        Map<String, String> metadataFilter,
                        String alias) {

        String q = alias == null || alias.isBlank() ? "" : alias + ".";
        StringBuilder clause = new StringBuilder(q + "organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);

        // Half-open interval: inclusive lower bound, exclusive upper, so
        // adjacent ranges neither overlap nor drop the boundary instant.
        clause.append(" AND ").append(q).append("occurred_at >= ?");
        params.add(utc(from));
        clause.append(" AND ").append(q).append("occurred_at < ?");
        params.add(utc(to));

        if (eventType != null && !eventType.isBlank()) {
            clause.append(" AND ").append(q).append("event_type = ?");
            params.add(eventType);
        }

        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            clause.append(" AND ").append(q).append("metadata @> ?::jsonb");
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
