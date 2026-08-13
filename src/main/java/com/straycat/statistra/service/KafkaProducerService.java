package com.straycat.statistra.service;

import com.straycat.statistra.config.StatistraProperties;
import com.straycat.statistra.model.AnalyticsEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KafkaProducerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerService.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StatistraProperties properties;
    private final Counter published;
    private final Counter publishFailed;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate,
                                StatistraProperties properties,
                                MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.published = Counter.builder("statistra.events.published")
                .description("Events handed to Kafka")
                .register(meterRegistry);
        this.publishFailed = Counter.builder("statistra.events.publish_failed")
                .description("Events that could not be handed to Kafka")
                .register(meterRegistry);
    }

    public void send(AnalyticsEvent event) {
        String topic = properties.getKafka().getTopic();

        // Keying by organization gives a tenant's events partition affinity, so
        // they retain their relative order. A null key would round-robin them
        // across partitions and lose that ordering entirely.
        String key = String.valueOf(event.getOrganizationId());

        kafkaTemplate.send(topic, key, event).whenComplete((result, failure) -> {
            if (failure != null) {
                // Previously this was fire-and-forget, so a broker outage lost
                // events while clients still saw success. At minimum it is now
                // visible in logs and metrics.
                publishFailed.increment();
                log.error("Failed to publish event {} for organization {}",
                        event.getEventId(), event.getOrganizationId(), failure);
            } else {
                published.increment();
            }
        });
    }

    public void sendAll(List<AnalyticsEvent> events) {
        events.forEach(this::send);
    }
}
