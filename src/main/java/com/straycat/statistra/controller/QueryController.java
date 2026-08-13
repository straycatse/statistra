package com.straycat.statistra.controller;

import com.straycat.statistra.dto.query.QueryResponses.Breakdown;
import com.straycat.statistra.dto.query.QueryResponses.EventPage;
import com.straycat.statistra.dto.query.QueryResponses.EventTypeEntry;
import com.straycat.statistra.dto.query.QueryResponses.Funnel;
import com.straycat.statistra.dto.query.QueryResponses.Summary;
import com.straycat.statistra.dto.query.QueryResponses.TimeSeries;
import com.straycat.statistra.entity.Organization;
import com.straycat.statistra.security.CurrentOrganization;
import com.straycat.statistra.service.AnalyticsQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Read API.
 *
 * <p>Note that no endpoint accepts an organization id. Scope comes from the
 * authenticated key via {@link CurrentOrganization}, so there is no request a
 * caller can construct that reads another tenant's data.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final AnalyticsQueryService queryService;

    public QueryController(AnalyticsQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Event counts over time.
     *
     * <p>Example: {@code /api/v1/analytics/timeseries?interval=day&filter=plan:pro}
     */
    @GetMapping("/analytics/timeseries")
    public TimeSeries timeseries(
            @CurrentOrganization Organization organization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "day") String interval,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<String> filter) {

        return queryService.timeSeries(
                organization.getId(), from, to, interval, eventType, filter);
    }

    /**
     * Top groups by count.
     *
     * <p>{@code groupBy} is either {@code eventType} or {@code metadata.<key>}.
     */
    @GetMapping("/analytics/breakdown")
    public Breakdown breakdown(
            @CurrentOrganization Organization organization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "eventType") String groupBy,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<String> filter,
            @RequestParam(required = false) Integer limit) {

        return queryService.breakdown(
                organization.getId(), from, to, groupBy, eventType, filter, limit);
    }

    @GetMapping("/analytics/summary")
    public Summary summary(
            @CurrentOrganization Organization organization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<String> filter) {

        return queryService.summary(organization.getId(), from, to, eventType, filter);
    }

    @GetMapping("/events")
    public EventPage events(
            @CurrentOrganization Organization organization,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) List<String> filter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {

        return queryService.events(
                organization.getId(), from, to, eventType, filter, limit, offset);
    }

    /**
     * Ordered conversion funnel.
     *
     * <p>Example:
     * {@code /api/v1/analytics/funnel?step=page_view&step=signup&step=purchase&window=7d}
     *
     * <p>Steps are matched in the order given and each actor is counted once
     * per step. {@code window} bounds how long someone has to finish, measured
     * from their first step, and defaults to 7d.
     */
    @GetMapping("/analytics/funnel")
    public Funnel funnel(
            @CurrentOrganization Organization organization,
            @RequestParam List<String> step,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) List<String> filter) {

        return queryService.funnel(organization.getId(), from, to, step, window, filter);
    }

    @GetMapping("/event-types")
    public List<EventTypeEntry> eventTypes(@CurrentOrganization Organization organization) {
        return queryService.eventTypes(organization.getId());
    }
}
