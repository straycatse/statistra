package com.straycat.statistra.dto.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakdownDimensionTest {

    @Test
    void defaultsToEventType() {
        assertThat(BreakdownDimension.from(null).type())
                .isEqualTo(BreakdownDimension.Type.EVENT_TYPE);
        assertThat(BreakdownDimension.from("").type())
                .isEqualTo(BreakdownDimension.Type.EVENT_TYPE);
        assertThat(BreakdownDimension.from("eventType").type())
                .isEqualTo(BreakdownDimension.Type.EVENT_TYPE);
    }

    @Test
    void parsesMetadataKeys() {
        BreakdownDimension dimension = BreakdownDimension.from("metadata.plan");
        assertThat(dimension.type()).isEqualTo(BreakdownDimension.Type.METADATA);
        assertThat(dimension.metadataKey()).isEqualTo("plan");
    }

    @Test
    void rejectsUnknownDimensions() {
        assertThatThrownBy(() -> BreakdownDimension.from("organization_id"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMetadataKeysThatCouldNotBeSafelyGrouped() {
        assertThatThrownBy(() -> BreakdownDimension.from("metadata.plan'; DROP TABLE x;--"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
