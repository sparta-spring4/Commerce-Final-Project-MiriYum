package com.miriyum.domain.search.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HaversineDistanceCalculatorTest {

    @Test
    @DisplayName("동일 좌표의 거리는 0미터다")
    void returnsZeroForSameCoordinate() {
        GeoCoordinate seoul = new GeoCoordinate(37.5665, 126.9780);

        double distanceMeters = HaversineDistanceCalculator.distanceMeters(seoul, seoul);

        assertThat(distanceMeters).isZero();
    }

    @Test
    @DisplayName("좌표 입력 순서를 바꿔도 거리가 같다")
    void returnsSymmetricDistance() {
        GeoCoordinate seoul = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate suwon = new GeoCoordinate(37.2636, 127.0286);

        double fromSeoul = HaversineDistanceCalculator.distanceMeters(seoul, suwon);
        double fromSuwon = HaversineDistanceCalculator.distanceMeters(suwon, seoul);

        assertThat(fromSeoul).isEqualTo(fromSuwon);
    }

    @Test
    @DisplayName("평균 지구 반지름 6371008.8미터를 사용해 알려진 거리를 계산한다")
    void calculatesDistanceWithConfiguredMeanEarthRadius() {
        GeoCoordinate seoul = new GeoCoordinate(37.5665, 126.9780);
        GeoCoordinate busan = new GeoCoordinate(35.1796, 129.0756);

        double distanceMeters = HaversineDistanceCalculator.distanceMeters(seoul, busan);

        assertThat(distanceMeters).isCloseTo(325_111.71, within(0.01));
    }

    private static org.assertj.core.data.Offset<Double> within(double offset) {
        return org.assertj.core.data.Offset.offset(offset);
    }
}
