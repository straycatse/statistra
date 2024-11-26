package com.straycat.statistra.service;

import com.straycat.statistra.model.AnalyticsEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendAnalyticsEvent(String topic, AnalyticsEvent event) {
        kafkaTemplate.send(topic, event);
    }
}
