package com.miriyum.domain.store.recommendation.geo;

import java.util.Objects;

/**
 * 원 매장의 저장 좌표를 기준으로 후보 매장의 3km 포함 여부를 판정한다.
 */
public final class StoreDistanceEligibility {

    public static final double MAX_DISTANCE_METERS = 3_000.0;

    private StoreDistanceEligibility() {
    }

    /**
     * 후보 좌표가 원 좌표로부터 3km 이내인지 판정한다.
     *
     * <p>Bounding Box로 후보를 사전 판정한 뒤 Haversine 거리로 최종 판정한다.</p>
     *
     * @param origin 원 매장의 저장 좌표
     * @param candidate 후보 매장의 저장 좌표
     * @return 계산된 거리가 3,000미터 이하이면 {@code true}
     */
    public static boolean isWithinThreeKilometers(
            GeoCoordinate origin,
            GeoCoordinate candidate
    ) {
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        BoundingBox boundingBox = BoundingBoxCalculator.around(origin, MAX_DISTANCE_METERS);
        if (!boundingBox.contains(candidate)) {
            return false;
        }

        return isWithinDistance(HaversineDistanceCalculator.distanceMeters(origin, candidate));
    }

    static boolean isWithinDistance(double distanceMeters) {
        return Double.isFinite(distanceMeters)
                && distanceMeters >= 0.0
                && distanceMeters <= MAX_DISTANCE_METERS;
    }
}
