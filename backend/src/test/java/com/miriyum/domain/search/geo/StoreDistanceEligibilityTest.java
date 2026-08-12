package com.miriyum.domain.search.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoreDistanceEligibilityTest {

    @Test
    @DisplayName("계산된 거리가 정확히 3000미터면 포함하고 초과하면 제외한다")
    void appliesInclusiveThreeKilometerThreshold() {
        assertThat(StoreDistanceEligibility.isWithinDistance(3_000.0)).isTrue();
        assertThat(StoreDistanceEligibility.isWithinDistance(Math.nextUp(3_000.0))).isFalse();
        assertThat(StoreDistanceEligibility.isWithinDistance(-1.0)).isFalse();
        assertThat(StoreDistanceEligibility.isWithinDistance(Double.NaN)).isFalse();
        assertThat(StoreDistanceEligibility.isWithinDistance(Double.POSITIVE_INFINITY)).isFalse();
    }

    @Test
    @DisplayName("Bounding Box 밖의 좌표는 3km 후보에서 제외한다")
    void rejectsCandidateOutsideBoundingBox() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate outside = new GeoCoordinate(37.60, 126.9780);

        assertThat(StoreDistanceEligibility.isWithinThreeKilometers(origin, outside)).isFalse();
    }

    @Test
    @DisplayName("Bounding Box 안의 모서리라도 Haversine 거리가 3km를 넘으면 제외한다")
    void rejectsBoundingBoxCornerOutsideHaversineRadius() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        BoundingBox box = BoundingBoxCalculator.around(origin, 3_000.0);
        GeoCoordinate corner = new GeoCoordinate(box.maxLatitude(), box.maxLongitude());

        assertThat(box.contains(corner)).isTrue();
        assertThat(HaversineDistanceCalculator.distanceMeters(origin, corner)).isGreaterThan(3_000.0);
        assertThat(StoreDistanceEligibility.isWithinThreeKilometers(origin, corner)).isFalse();
    }

    @Test
    @DisplayName("대한민국 좌표의 3km 이내 후보를 허용한다")
    void acceptsKoreanCandidateWithinThreeKilometers() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate candidate = new GeoCoordinate(37.5665, 127.0);

        assertThat(StoreDistanceEligibility.isWithinThreeKilometers(origin, candidate)).isTrue();
    }

    @Test
    @DisplayName("3km 바로 안쪽 실제 좌표는 전체 판정에서 허용한다")
    void acceptsCoordinateJustInsideThreeKilometers() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate candidate = destination(origin, 2_999.999, 90.0);

        assertThat(HaversineDistanceCalculator.distanceMeters(origin, candidate))
                .isCloseTo(2_999.999, within(0.000_001));
        assertThat(StoreDistanceEligibility.isWithinThreeKilometers(origin, candidate)).isTrue();
    }

    @Test
    @DisplayName("3km 바로 바깥 실제 좌표는 전체 판정에서 제외한다")
    void rejectsCoordinateJustOutsideThreeKilometers() {
        GeoCoordinate origin = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate candidate = destination(origin, 3_000.001, 90.0);

        assertThat(HaversineDistanceCalculator.distanceMeters(origin, candidate))
                .isCloseTo(3_000.001, within(0.000_001));
        assertThat(StoreDistanceEligibility.isWithinThreeKilometers(origin, candidate)).isFalse();
    }

    private static GeoCoordinate destination(
            GeoCoordinate origin,
            double distanceMeters,
            double bearingDegrees
    ) {
        double angularDistance = distanceMeters / 6_371_008.8;
        double bearing = Math.toRadians(bearingDegrees);
        double latitude = Math.toRadians(origin.latitude());
        double longitude = Math.toRadians(origin.longitude());
        double destinationLatitude = Math.asin(
                Math.sin(latitude) * Math.cos(angularDistance)
                        + Math.cos(latitude)
                        * Math.sin(angularDistance)
                        * Math.cos(bearing)
        );
        double destinationLongitude = longitude + Math.atan2(
                Math.sin(bearing)
                        * Math.sin(angularDistance)
                        * Math.cos(latitude),
                Math.cos(angularDistance)
                        - Math.sin(latitude)
                        * Math.sin(destinationLatitude)
        );

        return new GeoCoordinate(
                Math.toDegrees(destinationLatitude),
                Math.toDegrees(destinationLongitude)
        );
    }

    private static org.assertj.core.data.Offset<Double> within(double offset) {
        return org.assertj.core.data.Offset.offset(offset);
    }
}
