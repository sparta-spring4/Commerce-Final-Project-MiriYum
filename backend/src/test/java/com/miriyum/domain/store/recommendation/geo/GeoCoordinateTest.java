package com.miriyum.domain.store.recommendation.geo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeoCoordinateTest {

    @Test
    @DisplayName("세계 좌표 유효 범위의 경계값을 허용한다")
    void acceptsGlobalCoordinateBoundaries() {
        assertThatCode(() -> new GeoCoordinate(-90.0, -180.0))
                .doesNotThrowAnyException();
        assertThatCode(() -> new GeoCoordinate(90.0, 180.0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유효 범위를 벗어난 위도와 경도를 거부한다")
    void rejectsCoordinatesOutsideGlobalRange() {
        assertThatThrownBy(() -> new GeoCoordinate(-90.000_001, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(90.000_001, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(37.0, -180.000_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(37.0, 180.000_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NaN과 양음의 무한대 좌표를 거부한다")
    void rejectsNonFiniteCoordinates() {
        assertThatThrownBy(() -> new GeoCoordinate(Double.NaN, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(37.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(Double.POSITIVE_INFINITY, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoCoordinate(37.0, Double.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
