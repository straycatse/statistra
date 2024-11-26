package com.straycat.statistra.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public class AnalyticsEvent {

    @NotBlank(message = "Event type is mandatory")
    private String eventType;

    @NotBlank(message = "Timestamp is mandatory")
    private String timestamp;

    @NotNull(message = "Metadata cannot be null")
    private Map<String, Object> metadata;

    @NotNull(message = "id required")
    private Long organizationId;

    // Getters and setters
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    @Override
    public String toString() {
        return "AnalyticsEvent{" +
                "eventType='" + eventType + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}