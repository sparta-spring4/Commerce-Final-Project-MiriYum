package com.miriyum.domain.store.recommendation.geo;

import java.util.Objects;

/**
 * 저장 좌표와 반경으로 Haversine 최종 판정 전에 사용할 사각 경계를 계산한다.
 */
public final class BoundingBoxCalculator {

    private BoundingBoxCalculator() {
    }

    /**
     * 기준 좌표를 중심으로 하는 사각 경계를 계산한다.
     *
     * <p>극지방에 닿거나 날짜변경선을 가로지르는 경계는 현재 지원하지 않는다.</p>
     *
     * @param center 기준 저장 좌표
     * @param radiusMeters 양의 유한한 반경(미터)
     * @return 후보 사전 필터용 경계
     * @throws IllegalArgumentException 반경이 유효하지 않거나 경계가 현재 지원 범위를 벗어나는 경우
     */
    public static BoundingBox around(GeoCoordinate center, double radiusMeters) {
        Objects.requireNonNull(center, "center must not be null");
        requireValidRadius(radiusMeters);

        double angularDistance = radiusMeters / HaversineDistanceCalculator.EARTH_RADIUS_METERS;
        double latitudeDelta = Math.toDegrees(angularDistance);
        double minLatitude = center.latitude() - latitudeDelta;
        double maxLatitude = center.latitude() + latitudeDelta;

        if (minLatitude <= -90.0 || maxLatitude >= 90.0) {
            throw new IllegalArgumentException("polar bounding boxes are not supported");
        }

        double centerLatitude = Math.toRadians(center.latitude());
        double longitudeRatio = Math.sin(angularDistance) / Math.cos(centerLatitude);
        if (!Double.isFinite(longitudeRatio)
                || longitudeRatio < -1.0
                || longitudeRatio > 1.0) {
            throw new IllegalArgumentException("polar bounding boxes are not supported");
        }

        double longitudeDelta = Math.toDegrees(Math.asin(longitudeRatio));
        double minLongitude = center.longitude() - longitudeDelta;
        double maxLongitude = center.longitude() + longitudeDelta;

        if (minLongitude < -180.0 || maxLongitude > 180.0) {
            throw new IllegalArgumentException("date-line crossing bounding boxes are not supported");
        }

        return new BoundingBox(minLatitude, maxLatitude, minLongitude, maxLongitude);
    }

    private static void requireValidRadius(double radiusMeters) {
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0.0) {
            throw new IllegalArgumentException("radiusMeters must be positive and finite");
        }
    }
}
