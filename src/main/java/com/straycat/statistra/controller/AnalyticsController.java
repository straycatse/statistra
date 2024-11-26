package com.straycat.statistra.controller;

import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.model.AnalyticsEvent;
import com.straycat.statistra.repository.OrganizationRepository;
import com.straycat.statistra.service.KafkaProducerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @Autowired
    private OrganizationRepository organizationRepository;

    private final KafkaProducerService kafkaProducerService;

    public AnalyticsController(KafkaProducerService kafkaProducerService) {
        this.kafkaProducerService = kafkaProducerService;
    }

    @PostMapping("/events")
    public ResponseEntity<String> sendEvent(@RequestBody AnalyticsEvent event, @RequestHeader("API-Key") String apiKey) {

        Organization organization = organizationRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API Key"));

        event.setOrganizationId(organization.getId());

        kafkaProducerService.sendAnalyticsEvent("analytics-events", event);
        System.out.println("Lol" + event);
        return ResponseEntity.ok("Event sent to Kafka!");
    }

    /*@Autowired
    private AnalyticsEventRepository analyticsEventRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @PostMapping("/events")
    public ResponseEntity<?> collectEvent(@RequestBody AnalyticsEvent analyticsEvent,
                                          @RequestHeader("API-Key") String apiKey) {
        // Fetch Organization based on API key
        Organization organization = organizationRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API Key"));

        // Create and save AnalyticsEventEntity
        // Map DTO to Entity
        // Map DTO to Entity
        AnalyticsEventEntity eventEntity = new AnalyticsEventEntity();
        eventEntity.setEventType(analyticsEvent.getEventType());
        eventEntity.setMetadata(analyticsEvent.getMetadata()); // Directly set the Map
        eventEntity.setTimestamp(analyticsEvent.getTimestamp());
        eventEntity.setOrganization(organization);

        // Save entity
        analyticsEventRepository.save(eventEntity);

        return ResponseEntity.ok("Event saved successfully.");
    }*/
}