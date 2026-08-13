package com.straycat.statistra.service;

import com.straycat.statistra.dao.AnalyticsEventDao;
import com.straycat.statistra.model.AnalyticsEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch handling, in particular what happens to good records that share a batch
 * with an unparseable one.
 */
class KafkaConsumerServiceTest {

    private RecordingDao dao;
    private RecordingAck acknowledgment;
    private KafkaConsumerService service;

    @BeforeEach
    void setUp() {
        dao = new RecordingDao();
        acknowledgment = new RecordingAck();
        service = new KafkaConsumerService(dao, new SimpleMeterRegistry());
    }

    @Test
    void persistsAWholeBatchInOneCall() {
        service.consume(List.of(event("a"), event("b"), event("c")), acknowledgment);

        assertThat(dao.calls).isEqualTo(1);
        assertThat(dao.lastBatch).hasSize(3);
        assertThat(acknowledgment.acknowledged).isTrue();
    }

    @Test
    void discardsUnparseableRecordsButStillPersistsTheRest() {
        // ErrorHandlingDeserializer substitutes null for a record it cannot
        // parse. Previously this threw, which failed the batch and eventually
        // dead-lettered the two perfectly good records alongside the bad one.
        List<AnalyticsEvent> batch = Arrays.asList(event("a"), null, event("c"));

        service.consume(batch, acknowledgment);

        assertThat(dao.lastBatch).hasSize(2);
        assertThat(dao.lastBatch).noneMatch(java.util.Objects::isNull);
        assertThat(acknowledgment.acknowledged).isTrue();
    }

    @Test
    void acknowledgesAndSkipsTheDatabaseWhenEveryRecordIsUnparseable() {
        service.consume(Arrays.asList(null, null), acknowledgment);

        assertThat(dao.calls).isZero();
        // Still acknowledged: retrying records that can never parse would block
        // the partition indefinitely.
        assertThat(acknowledgment.acknowledged).isTrue();
    }

    @Test
    void handlesAnEmptyBatch() {
        service.consume(Collections.emptyList(), acknowledgment);

        assertThat(dao.calls).isZero();
        assertThat(acknowledgment.acknowledged).isTrue();
    }

    private AnalyticsEvent event(String type) {
        return new AnalyticsEvent(UUID.randomUUID(), 1L, type, Instant.now(), Map.of());
    }

    private static final class RecordingDao extends AnalyticsEventDao {
        private int calls;
        private List<AnalyticsEvent> lastBatch = new ArrayList<>();

        private RecordingDao() {
            super(null, null);
        }

        @Override
        public int insertBatch(List<AnalyticsEvent> events) {
            calls++;
            lastBatch = new ArrayList<>(events);
            return events.size();
        }
    }

    private static final class RecordingAck implements Acknowledgment {
        private boolean acknowledged;

        @Override
        public void acknowledge() {
            acknowledged = true;
        }
    }
}
