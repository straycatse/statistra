package com.straycat.statistra.dto.query;

import java.util.Locale;

/**
 * Bucket sizes for time-series queries.
 *
 * <p>An enum rather than a free string on purpose: the value is interpolated
 * into {@code date_trunc} and {@code generate_series}, so constraining it to a
 * closed set is what keeps that safe.
 */
public enum TimeInterval {

    HOUR("hour", "1 hour"),
    DAY("day", "1 day"),
    WEEK("week", "1 week"),
    MONTH("month", "1 month");

    private final String truncField;
    private final String step;

    TimeInterval(String truncField, String step) {
        this.truncField = truncField;
        this.step = step;
    }

    /** The unit passed to {@code date_trunc}. Always one of the constants above. */
    public String truncField() {
        return truncField;
    }

    /** The stride passed to {@code generate_series}, for gap filling. */
    public String step() {
        return step;
    }

    public static TimeInterval from(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown interval '" + value + "'. Expected one of: hour, day, week, month");
        }
    }
}
