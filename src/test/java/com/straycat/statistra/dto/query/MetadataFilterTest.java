package com.straycat.statistra.dto.query;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterTest {

    @Test
    void parsesKeyValuePairs() {
        Map<String, String> parsed = MetadataFilter.parse(List.of("plan:pro", "country:SE"));
        assertThat(parsed).containsExactly(
                Map.entry("plan", "pro"),
                Map.entry("country", "SE"));
    }

    @Test
    void absentOrEmptyFiltersYieldNoConstraint() {
        assertThat(MetadataFilter.parse(null)).isEmpty();
        assertThat(MetadataFilter.parse(Collections.emptyList())).isEmpty();
    }

    @Test
    void valuesMayContainColons() {
        // Splitting on the first colon only, so URLs and timestamps survive.
        assertThat(MetadataFilter.parse(List.of("referrer:https://example.com/x")))
                .containsEntry("referrer", "https://example.com/x");
    }

    @Test
    void rejectsFiltersWithoutASeparator() {
        assertThatThrownBy(() -> MetadataFilter.parse(List.of("plan")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key:value");
    }

    @Test
    void rejectsEmptyKeyOrValue() {
        assertThatThrownBy(() -> MetadataFilter.parse(List.of(":pro")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MetadataFilter.parse(List.of("plan:")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnExcessiveNumberOfFilters() {
        List<String> many = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "key" + i + ":value")
                .toList();
        assertThatThrownBy(() -> MetadataFilter.parse(many))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At most");
    }

    @Test
    void rejectsKeysCarryingSqlMetacharacters() {
        // Keys are bound as parameters rather than interpolated, so this is
        // belt and braces. It still matters: the grouping path validates with
        // the same rule, and a uniform reject keeps the two consistent.
        List<String> hostile = List.of(
                "plan';DROP TABLE analytics_events;--:x",
                "plan\":y",
                "plan key:y",
                "plan(:y",
                "'':y");

        for (String filter : hostile) {
            assertThatThrownBy(() -> MetadataFilter.parse(List.of(filter)))
                    .as("filter %s", filter)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsConventionalKeyShapes() {
        assertThat(MetadataFilter.parse(List.of(
                "plan:pro", "user_id:1", "app.version:2.1", "utm-source:x")))
                .containsKeys("plan", "user_id", "app.version", "utm-source");
    }

    @Test
    void rejectsOverlongKeys() {
        String key = "k".repeat(65);
        assertThatThrownBy(() -> MetadataFilter.parse(List.of(key + ":v")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
