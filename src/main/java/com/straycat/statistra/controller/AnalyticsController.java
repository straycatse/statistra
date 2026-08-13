package com.straycat.statistra.controller;

import com.straycat.statistra.config.StatistraProperties;
import com.straycat.statistra.dto.IngestBatchRequest;
import com.straycat.statistra.dto.IngestEventRequest;
import com.straycat.statistra.dto.IngestResponse;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.model.AnalyticsEvent;
import com.straycat.statistra.security.CurrentOrganization;
import com.straycat.statistra.service.KafkaProducerService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Event ingest. Accepts, enqueues, and returns; persistence happens
 * asynchronously in {@link com.straycat.statistra.service.KafkaConsumerService}.
 */
@RestController
@RequestMapping("/api/v1/events")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final KafkaProducerService kafkaProducerService;
    private final StatistraProperties properties;

    public AnalyticsController(KafkaProducerService kafkaProducerService,
                               StatistraProperties properties) {
        this.kafkaProducerService = kafkaProducerService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(
            @Valid @RequestBody IngestEventRequest request,
            @CurrentOrganization Organization organization) {

        AnalyticsEvent event = toEvent(request, organization);
        kafkaProducerService.send(event);

        log.debug("Accepted event {} for organization {}", event.getEventId(), organization.getId());
        return ResponseEntity.accepted().body(IngestResponse.single(event.getEventId()));
    }

    @PostMapping("/batch")
    public ResponseEntity<IngestResponse> ingestBatch(
            @Valid @RequestBody IngestBatchRequest request,
            @CurrentOrganization Organization organization) {

        int maxBatchSize = properties.getIngest().getMaxBatchSize();
        if (request.events().size() > maxBatchSize) {
            throw new IllegalArgumentException(
                    "A batch may contain at most " + maxBatchSize + " events, received "
                            + request.events().size());
        }

        List<AnalyticsEvent> events = request.events().stream()
                .map(e -> toEvent(e, organization))
                .toList();
        kafkaProducerService.sendAll(events);

        List<UUID> ids = events.stream().map(AnalyticsEvent::getEventId).toList();
        log.debug("Accepted {} events for organization {}", ids.size(), organization.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new IngestResponse(ids.size(), ids));
    }

    /**
     * The organization id is taken from the authenticated principal and never
     * from the request body, which is what prevents one tenant writing into
     * another's data.
     */
    private AnalyticsEvent toEvent(IngestEventRequest request, Organization organization) {
        return new AnalyticsEvent(
                request.eventIdOrRandom(),
                organization.getId(),
                request.eventType(),
                request.occurredAtOrNow(),
                request.metadataOrEmpty());
    }
}
