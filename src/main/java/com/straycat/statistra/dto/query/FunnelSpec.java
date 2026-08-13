package com.straycat.statistra.dto.query;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A validated funnel definition.
 *
 * <p>Steps are ordered and matched first-occurrence-first: for each actor the
 * earliest step-one event is taken, then the earliest step-two event strictly
 * after it, and so on. An actor who did step two before step one has not
 * converted, which is the whole point of a funnel as opposed to a set of
 * independent counts.
 *
 * @param steps          event types in order, at least two
 * @param conversionWindow how long an actor has to complete every remaining
 *                       step, measured from their step-one event. Without a
 *                       bound, someone who signed up two years after reading the
 *                       landing page counts as a conversion, which makes the
 *                       number meaningless.
 */
public record FunnelSpec(List<String> steps, Duration conversionWindow) {

    /** Enough for any funnel worth reading, and bounds the generated SQL. */
    public static final int MAX_STEPS = 8;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final Duration MAX_WINDOW = Duration.ofDays(365);
    private static final Pattern WINDOW = Pattern.compile("^(\\d{1,6})([mhd])$");

    public FunnelSpec {
        if (steps == null || steps.size() < 2) {
            throw new IllegalArgumentException(
                    "A funnel needs at least 2 steps. Pass step= once per step, in order.");
        }
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "A funnel may have at most " + MAX_STEPS + " steps, got " + steps.size());
        }
        steps.forEach(step -> {
            if (step == null || step.isBlank()) {
                throw new IllegalArgumentException("Funnel steps must be non-empty event types");
            }
        });
        steps = List.copyOf(steps);
    }

    public static FunnelSpec of(List<String> steps, String window) {
        return new FunnelSpec(steps, parseWindow(window));
    }

    /**
     * Accepts {@code 30m}, {@code 24h}, {@code 7d}. Deliberately not ISO-8601:
     * this is a URL parameter people type by hand, and {@code PT168H} is not.
     */
    static Duration parseWindow(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_WINDOW;
        }
        Matcher m = WINDOW.matcher(value.trim().toLowerCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Unknown window '" + value + "'. Expected a number followed by m, h or d, "
                            + "for example 30m, 24h or 7d");
        }
        long n = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
        if (d.isZero()) {
            throw new IllegalArgumentException("Conversion window must be greater than zero");
        }
        if (d.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("Conversion window may be at most 365d");
        }
        return d;
    }
}
