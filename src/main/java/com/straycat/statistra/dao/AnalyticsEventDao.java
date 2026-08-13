package com.straycat.statistra.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.straycat.statistra.model.AnalyticsEvent;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Write path for {@code analytics_events}.
 *
 * <p>Deliberately not JPA. The insert below uses {@code ON CONFLICT DO NOTHING},
 * which JPA has no way to express: the alternatives inside an ORM are
 * select-then-insert, which races under concurrent delivery, or catching a
 * constraint violation per row, which marks the surrounding transaction
 * rollback-only and takes the whole batch down with it. Since deduplication is
 * the correctness guarantee that keeps counts accurate under Kafka's
 * at-least-once delivery, the ORM simply cannot do the job here.
 */
@Repository
public class AnalyticsEventDao {

    private static final String INSERT_SQL = """
            INSERT INTO analytics_events
                (event_id, organization_id, event_type, occurred_at, metadata)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (organization_id, event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsEventDao(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a batch in one round trip, skipping events already stored.
     *
     * @return how many rows were actually written. A shortfall against
     *         {@code events.size()} is duplicate suppression working, not an error.
     */
    public int insertBatch(List<AnalyticsEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }

        int[] affected = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AnalyticsEvent event = events.get(i);
                ps.setObject(1, event.getEventId());
                ps.setLong(2, event.getOrganizationId());
                ps.setString(3, event.getEventType());
                // An explicit UTC OffsetDateTime rather than a java.sql.Timestamp.
                // Timestamp carries no zone, so the driver resolves it against
                // the JVM default and Postgres against the session TimeZone.
                // Those agree today only because pgjdbc sets the latter from the
                // former at connection time. Binding the offset directly removes
                // the dependency, and matches how the read path reads the column
                // back and how AnalyticsQueryDao binds its bounds.
                ps.setObject(4, event.getOccurredAt().atOffset(ZoneOffset.UTC));
                // Bound as text and cast by the ?::jsonb in the statement, so
                // this class never has to compile against the Postgres driver.
                ps.setString(5, serialiseMetadata(event));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });

        int inserted = 0;
        for (int count : affected) {
            // A suppressed conflict reports 0 rows affected.
            if (count > 0) {
                inserted += count;
            }
        }
        return inserted;
    }

    private String serialiseMetadata(AnalyticsEvent event) throws SQLException {
        Map<String, Object> metadata = event.getMetadata() == null ? Map.of() : event.getMetadata();
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new SQLException("Could not serialise metadata for event " + event.getEventId(), e);
        }
    }
}
