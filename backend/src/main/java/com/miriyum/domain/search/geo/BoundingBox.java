package com.miriyum.domain.search.geo;

import java.util.Objects;

/**
 * 날짜변경선을 가로지르지 않는 위도·경도 사각 경계를 나타낸다.
 *
 * @param minLatitude 최소 위도
 * @param maxLatitude 최대 위도
 * @param minLongitude 최소 경도
 * @param maxLongitude 최대 경도
 */
public record BoundingBox(
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude
) {

    public BoundingBox {
        requireBound(minLatitude, -90.0, 90.0, "minLatitude");
        requireBound(maxLatitude, -90.0, 90.0, "maxLatitude");
        requireBound(minLongitude, -180.0, 180.0, "minLongitude");
        requireBound(maxLongitude, -180.0, 180.0, "maxLongitude");
        if (minLatitude > maxLatitude || minLongitude > maxLongitude) {
            throw new IllegalArgumentException("minimum bounds must not exceed maximum bounds");
        }
    }

    /**
     * 좌표가 경계를 포함한 사각 영역 안에 있는지 판정한다.
     *
     * @param coordinate 판정할 좌표
     * @return 네 경계를 포함한 영역 안이면 {@code true}
     */
    public boolean contains(GeoCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate must not be null");
        return coordinate.latitude() >= minLatitude
                && coordinate.latitude() <= maxLatitude
                && coordinate.longitude() >= minLongitude
                && coordinate.longitude() <= maxLongitude;
    }

    private static void requireBound(double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be finite and between " + minimum + " and " + maximum
            );
        }
    }
}
