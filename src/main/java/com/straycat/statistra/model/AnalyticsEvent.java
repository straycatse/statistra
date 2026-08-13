package com.straycat.statistra.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The internal event representation carried over Kafka.
 *
 * <p>Distinct from {@link com.straycat.statistra.dto.IngestEventRequest}, which
 * is the untrusted client-facing shape. By the time an event becomes one of
 * these it has been validated and its {@code organizationId} set from the
 * authenticated API key, never from the request body.
 */
public class AnalyticsEvent {

    private UUID eventId;
    private Long organizationId;
    private String eventType;
    private Instant occurredAt;
    private Map<String, Object> metadata;

    public AnalyticsEvent() {
        // Required by the JSON deserializer.
    }

    public AnalyticsEvent(UUID eventId,
                          Long organizationId,
                          String eventType,
                          Instant occurredAt,
                          Map<String, Object> metadata) {
        this.eventId = eventId;
        this.organizationId = organizationId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.metadata = metadata;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @Override
    public String toString() {
        // Metadata is deliberately summarised rather than printed. It is
        // arbitrary client data and may contain personal information.
        return "AnalyticsEvent{eventId=" + eventId
                + ", organizationId=" + organizationId
                + ", eventType='" + eventType + '\''
                + ", occurredAt=" + occurredAt
                + ", metadataKeys=" + (metadata == null ? 0 : metadata.size())
                + '}';
    }
}
