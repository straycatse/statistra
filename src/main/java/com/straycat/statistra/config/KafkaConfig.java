package com.straycat.statistra.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring.
 *
 * <p>Note what is <em>not</em> here: no broker address, no serializer classes, no
 * consumer group. Those all live in {@code application.properties} under
 * {@code spring.kafka.*} and are read by Boot's auto-configuration. The previous
 * version declared its own producer and consumer factories with a hardcoded
 * {@code localhost:9092}, which both prevented deployment anywhere else and
 * silently made every {@code spring.kafka.*} property dead config.
 *
 * <p>What remains is only what auto-configuration cannot infer: topic creation
 * and the error handling policy.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final StatistraProperties properties;

    public KafkaConfig(StatistraProperties properties) {
        this.properties = properties;
    }

    @Bean
    public NewTopic analyticsEventsTopic() {
        return TopicBuilder.name(properties.getKafka().getTopic())
                .partitions(properties.getKafka().getPartitions())
                .replicas(properties.getKafka().getReplicationFactor())
                .build();
    }

    @Bean
    public NewTopic analyticsEventsDeadLetterTopic() {
        return TopicBuilder.name(properties.getKafka().getDeadLetterTopic())
                .partitions(properties.getKafka().getPartitions())
                .replicas(properties.getKafka().getReplicationFactor())
                .build();
    }

    /**
     * Retries a failing batch with backoff, then routes the offending records to
     * {@code <topic>.DLT} rather than dropping them.
     *
     * <p>Without this, a single malformed or unprocessable record is retried
     * indefinitely and blocks the partition behind it, or is discarded with
     * nothing but a log line. Dead-lettering makes the failure inspectable and
     * lets the rest of the stream continue.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> {
                    log.error("Dead-lettering record from {} partition {} offset {}",
                            record.topic(), record.partition(), record.offset(), exception);
                    return new org.apache.kafka.common.TopicPartition(
                            properties.getKafka().getDeadLetterTopic(), record.partition());
                });

        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(30_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
