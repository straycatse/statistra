package com.straycat.statistra.dto.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeIntervalTest {

    @Test
    void defaultsToDay() {
        assertThat(TimeInterval.from(null)).isEqualTo(TimeInterval.DAY);
        assertThat(TimeInterval.from("  ")).isEqualTo(TimeInterval.DAY);
    }

    @Test
    void parsesCaseInsensitively() {
        assertThat(TimeInterval.from("HOUR")).isEqualTo(TimeInterval.HOUR);
        assertThat(TimeInterval.from("week")).isEqualTo(TimeInterval.WEEK);
        assertThat(TimeInterval.from(" Month ")).isEqualTo(TimeInterval.MONTH);
    }

    @Test
    void rejectsAnythingOutsideTheClosedSet() {
        assertThatThrownBy(() -> TimeInterval.from("century"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hour, day, week, month");

        // The value reaches date_trunc, so an open string here would be the one
        // place a caller could influence SQL structure.
        assertThatThrownBy(() -> TimeInterval.from("day'); DROP TABLE analytics_events;--"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
