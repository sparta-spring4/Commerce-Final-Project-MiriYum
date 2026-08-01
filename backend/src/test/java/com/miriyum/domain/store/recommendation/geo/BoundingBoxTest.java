package com.miriyum.domain.store.recommendation.geo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundingBoxTest {

    @Test
    @DisplayName("유한하지 않거나 세계 좌표 범위를 벗어난 경계를 거부한다")
    void rejectsInvalidGlobalBounds() {
        assertThatThrownBy(() -> new BoundingBox(Double.NaN, 37.0, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new BoundingBox(36.0, Double.POSITIVE_INFINITY, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(-90.000_001, 37.0, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(36.0, 90.000_001, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(36.0, 37.0, -180.000_001, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(36.0, 37.0, 126.0, 180.000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최소 경계가 최대 경계를 넘으면 거부한다")
    void rejectsReversedBounds() {
        assertThatThrownBy(() -> new BoundingBox(38.0, 37.0, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(36.0, 37.0, 128.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
