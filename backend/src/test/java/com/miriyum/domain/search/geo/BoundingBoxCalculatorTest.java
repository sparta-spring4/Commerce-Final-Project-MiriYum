package com.miriyum.domain.search.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundingBoxCalculatorTest {

    @Test
    @DisplayName("대한민국 기준 좌표와 반경으로 후보 사전 필터 경계를 계산한다")
    void calculatesBoundingBoxForKoreanCoordinate() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);

        BoundingBox box = BoundingBoxCalculator.around(origin, 3_000.0);

        assertThat(box.contains(origin)).isTrue();
        assertThat(box.minLatitude()).isCloseTo(37.539_52, within(0.000_01));
        assertThat(box.maxLatitude()).isCloseTo(37.593_48, within(0.000_01));
        assertThat(box.minLongitude()).isCloseTo(126.943_97, within(0.000_01));
        assertThat(box.maxLongitude()).isCloseTo(127.012_03, within(0.000_01));
    }

    @Test
    @DisplayName("Bounding Box 경계는 포함하고 경계 밖 후보는 제외한다")
    void prefiltersCandidatesByInclusiveBounds() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        BoundingBox box = BoundingBoxCalculator.around(origin, 3_000.0);

        assertThat(box.contains(new GeoCoordinate(box.minLatitude(), box.minLongitude()))).isTrue();
        assertThat(box.contains(new GeoCoordinate(box.maxLatitude(), box.maxLongitude()))).isTrue();
        assertThat(box.contains(new GeoCoordinate(box.maxLatitude() + 0.000_001, origin.longitude()))).isFalse();
    }

    @Test
    @DisplayName("양수가 아니거나 유한하지 않은 반경을 거부한다")
    void rejectsInvalidRadius() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);

        assertThatThrownBy(() -> BoundingBoxCalculator.around(origin, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundingBoxCalculator.around(origin, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundingBoxCalculator.around(origin, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundingBoxCalculator.around(origin, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("극지방 또는 날짜변경선을 가로지르는 Bounding Box를 거부한다")
    void rejectsUnsupportedPolarOrDateLineBoundingBox() {
        assertThatThrownBy(() ->
                BoundingBoxCalculator.around(new GeoCoordinate(89.999, 127.0), 3_000.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                BoundingBoxCalculator.around(new GeoCoordinate(37.0, 179.999), 3_000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("3km 구면 원의 동쪽 최대 경도 접점을 Bounding Box에 포함한다")
    void containsEasternLongitudeTangentOfSphericalRadius() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate tangent = easternLongitudeTangent(origin, 3_000.0);

        BoundingBox box = BoundingBoxCalculator.around(origin, 3_000.0);

        assertThat(HaversineDistanceCalculator.distanceMeters(origin, tangent))
                .isCloseTo(3_000.0, within(0.000_001));
        assertThat(box.contains(tangent)).isTrue();
    }

    private static GeoCoordinate easternLongitudeTangent(
            GeoCoordinate origin,
            double distanceMeters
    ) {
        double angularDistance = distanceMeters / 6_371_008.8;
        double latitude = Math.toRadians(origin.latitude());
        double tangentLatitude = Math.asin(Math.sin(latitude) / Math.cos(angularDistance));
        double longitudeDelta = Math.asin(Math.sin(angularDistance) / Math.cos(latitude));

        return new GeoCoordinate(
                Math.toDegrees(tangentLatitude),
                origin.longitude() + Math.toDegrees(longitudeDelta)
        );
    }

    private static org.assertj.core.data.Offset<Double> within(double offset) {
        return org.assertj.core.data.Offset.offset(offset);
    }
}
