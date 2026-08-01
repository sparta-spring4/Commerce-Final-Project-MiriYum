package com.miriyum.domain.store.recommendation.geo;

/**
 * 전 세계 유효 범위의 위도와 경도를 나타낸다.
 *
 * @param latitude 위도, -90도 이상 90도 이하
 * @param longitude 경도, -180도 이상 180도 이하
 */
public record GeoCoordinate(double latitude, double longitude) {

    public GeoCoordinate {
        requireFinite(latitude, "latitude");
        requireFinite(longitude, "longitude");
        requireRange(latitude, -90.0, 90.0, "latitude");
        requireRange(longitude, -180.0, 180.0, "longitude");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireRange(double value, double minimum, double maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
