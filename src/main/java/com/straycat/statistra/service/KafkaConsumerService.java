package com.straycat.statistra.service;


import com.straycat.statistra.entity.AnalyticsEventEntity;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.model.AnalyticsEvent;
import com.straycat.statistra.repository.AnalyticsEventRepository;
import com.straycat.statistra.repository.OrganizationRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class KafkaConsumerService {

    private final AnalyticsEventRepository analyticsEventRepository;
    private final OrganizationRepository organizationRepository;

    public KafkaConsumerService(AnalyticsEventRepository analyticsEventRepository, OrganizationRepository organizationRepository) {
        this.analyticsEventRepository = analyticsEventRepository;
        this.organizationRepository = organizationRepository;
    }

    @KafkaListener(topics = "analytics-events", groupId = "analytics-group", containerFactory = "analyticsKafkaListenerContainerFactory")
    public void consumeAnalyticsEvent(AnalyticsEvent event) {
        System.out.println("Consumed event from Kafka: " + event);

        // Fetch the organization using the organizationId in the event
        Organization organization = organizationRepository.findById(event.getOrganizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organization not found for ID: " + event.getOrganizationId()));


        // Convert DTO to Entity
        AnalyticsEventEntity entity = new AnalyticsEventEntity();
        entity.setEventType(event.getEventType());
        entity.setMetadata(event.getMetadata()); // Ensure metadata is handled correctly
        entity.setTimestamp(event.getTimestamp() != null ? event.getTimestamp() : String.valueOf(LocalDateTime.now()));
        entity.setOrganization(organization);

        // Save to database
        analyticsEventRepository.save(entity);
        System.out.println("Saved event to database: " + entity);
    }
}