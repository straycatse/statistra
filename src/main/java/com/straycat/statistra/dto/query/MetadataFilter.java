package com.straycat.statistra.dto.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses {@code ?filter=key:value} parameters into a JSON containment operand.
 *
 * <p>Filtering is expressed as {@code metadata @> ?::jsonb} with the whole
 * filter object bound as a single parameter. That has two properties worth
 * being explicit about:
 *
 * <ul>
 *   <li>No user input ever reaches the SQL text, so the filter cannot be used
 *       to inject.</li>
 *   <li>Containment is exactly the operator the {@code jsonb_path_ops} GIN
 *       index supports, so filters use the index rather than scanning.</li>
 * </ul>
 */
public final class MetadataFilter {

    /**
     * Permitted metadata key shape. Redundant given that keys are bound as
     * parameters rather than concatenated, but grouping (see
     * {@link BreakdownDimension}) does need a validated key, and rejecting odd
     * keys uniformly keeps the two paths consistent.
     */
    private static final Pattern VALID_KEY = Pattern.compile("^[a-zA-Z0-9_.\\-]{1,64}$");
    private static final int MAX_FILTERS = 20;

    private MetadataFilter() {
    }

    /**
     * @param filters raw {@code key:value} strings from the query string
     * @return an ordered map suitable for serialising into the containment operand
     * @throws IllegalArgumentException if a filter is malformed
     */
    public static Map<String, String> parse(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Map.of();
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException(
                    "At most " + MAX_FILTERS + " filters may be supplied");
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String filter : filters) {
            int separator = filter.indexOf(':');
            if (separator <= 0 || separator == filter.length() - 1) {
                throw new IllegalArgumentException(
                        "Filter '" + filter + "' must be in key:value form");
            }
            String key = filter.substring(0, separator);
            String value = filter.substring(separator + 1);
            parsed.put(validateKey(key), value);
        }
        return parsed;
    }

    public static String validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Metadata key '" + key + "' must match " + VALID_KEY.pattern());
        }
        return key;
    }
}
