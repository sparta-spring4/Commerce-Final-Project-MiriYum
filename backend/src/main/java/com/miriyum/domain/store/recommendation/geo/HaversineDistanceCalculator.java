package com.miriyum.domain.store.recommendation.geo;

import java.util.Objects;

/**
 * 평균 지구 반지름을 사용하는 두 저장 좌표 사이의 대권 거리를 계산한다.
 */
public final class HaversineDistanceCalculator {

    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private HaversineDistanceCalculator() {
    }

    /**
     * 두 좌표 사이의 Haversine 거리를 미터 단위로 계산한다.
     *
     * @param first 첫 번째 좌표
     * @param second 두 번째 좌표
     * @return 0 이상인 거리(미터)
     */
    public static double distanceMeters(GeoCoordinate first, GeoCoordinate second) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");

        double firstLatitude = Math.toRadians(first.latitude());
        double secondLatitude = Math.toRadians(second.latitude());
        double latitudeDifference = secondLatitude - firstLatitude;
        double longitudeDifference = Math.toRadians(second.longitude() - first.longitude());

        double latitudeHalfChord = Math.sin(latitudeDifference / 2.0);
        double longitudeHalfChord = Math.sin(longitudeDifference / 2.0);
        double haversine = latitudeHalfChord * latitudeHalfChord
                + Math.cos(firstLatitude)
                * Math.cos(secondLatitude)
                * longitudeHalfChord
                * longitudeHalfChord;
        double centralAngle = 2.0 * Math.asin(Math.sqrt(Math.min(1.0, haversine)));

        return EARTH_RADIUS_METERS * centralAngle;
    }
}
