package com.straycat.statistra.service;

import com.straycat.statistra.dao.AnalyticsEventDao;
import com.straycat.statistra.model.AnalyticsEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Persists events from Kafka.
 *
 * <p>Three things changed from the original per-record listener:
 *
 * <ul>
 *   <li>It consumes batches. One insert per record via JPA was the throughput
 *       ceiling, and ingest volume is this service's entire load profile.</li>
 *   <li>Writes deduplicate. Kafka redelivers on rebalance or retry, and without
 *       suppression those redeliveries inflate every count the product reports.</li>
 *   <li>The offset is acknowledged only after the transaction commits, so a
 *       crash mid-batch replays the batch instead of losing it.</li>
 * </ul>
 */
@Service
public class KafkaConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerService.class);

    private final AnalyticsEventDao analyticsEventDao;
    private final Counter persisted;
    private final Counter deduplicated;
    private final Counter invalid;

    public KafkaConsumerService(AnalyticsEventDao analyticsEventDao, MeterRegistry meterRegistry) {
        this.analyticsEventDao = analyticsEventDao;
        this.persisted = Counter.builder("statistra.events.persisted")
                .description("Events written to the database")
                .register(meterRegistry);
        this.deduplicated = Counter.builder("statistra.events.deduplicated")
                .description("Redelivered events suppressed by the idempotency constraint")
                .register(meterRegistry);
        this.invalid = Counter.builder("statistra.events.invalid")
                .description("Records that could not be deserialised and were discarded")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${statistra.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void consume(List<AnalyticsEvent> events, Acknowledgment acknowledgment) {
        // ErrorHandlingDeserializer yields a null in place of a record it could
        // not parse. Those are dropped here rather than allowed to fail the
        // batch: with a batch listener a single unparseable record would take
        // the whole batch down, and after retries every valid record that
        // happened to share it would be dead-lettered alongside the bad one.
        List<AnalyticsEvent> valid = events.stream().filter(Objects::nonNull).toList();
        int unparseable = events.size() - valid.size();
        if (unparseable > 0) {
            invalid.increment(unparseable);
            log.warn("Discarded {} unparseable record(s) in a batch of {}", unparseable, events.size());
        }

        if (valid.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }

        int inserted = analyticsEventDao.insertBatch(valid);
        int suppressed = valid.size() - inserted;

        persisted.increment(inserted);
        if (suppressed > 0) {
            deduplicated.increment(suppressed);
            log.debug("Suppressed {} duplicate event(s) of {} received", suppressed, events.size());
        }

        // Only now is it safe to advance the offset. Acknowledging before the
        // insert would turn any failure into permanent data loss.
        acknowledgment.acknowledge();
        log.debug("Persisted {} of {} events", inserted, valid.size());
    }
}
