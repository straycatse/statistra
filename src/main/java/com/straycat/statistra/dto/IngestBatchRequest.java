package com.straycat.statistra.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * A batch of events.
 *
 * <p>{@code @Valid} on the list element type is what makes validation recurse
 * into each event rather than stopping at the list itself.
 *
 * <p>The upper bound is deliberately <em>not</em> a {@code @Size} annotation.
 * Annotation attributes must be compile-time constants, so a hardcoded limit
 * here silently overrode {@code statistra.ingest.max-batch-size} and left the
 * documented {@code MAX_BATCH_SIZE} knob doing nothing. The check now lives in
 * {@link com.straycat.statistra.controller.AnalyticsController}, where it can
 * read the configured value.
 */
public record IngestBatchRequest(
        @NotEmpty(message = "events must not be empty")
        List<@Valid IngestEventRequest> events) {
}
