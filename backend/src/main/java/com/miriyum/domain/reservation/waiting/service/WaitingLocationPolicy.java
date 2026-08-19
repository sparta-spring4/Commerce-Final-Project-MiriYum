package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.IntegrityStatus;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.MeasurementStatus;
import com.miriyum.domain.reservation.waiting.dto.WaitingLocationProofContracts.Request;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.AccuracyCategory;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.ResultCategory;
import com.miriyum.domain.store.dto.contract.StoreWaitingLocationProfile;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;

/** WAITING_LOCATION_V1의 거리·정확도·시간 판정을 한 곳에서 수행한다. */
final class WaitingLocationPolicy {

    static final String VERSION = "WAITING_LOCATION_V1";
    static final BigDecimal RADIUS_METERS = new BigDecimal("3000");
    static final BigDecimal MAX_ACCURACY_METERS = new BigDecimal("100");
    static final Duration MAX_MEASUREMENT_AGE = Duration.ofSeconds(30);
    static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(5);
    static final Duration PROOF_TTL = Duration.ofSeconds(120);
    private static final double EARTH_RADIUS_METERS = 6_371_008.8d;

    private WaitingLocationPolicy() { }

    static Judgment judge(StoreWaitingLocationProfile store, Request request, Instant now) {
        if (!store.locationProofEligible()) {
            return new Judgment(ResultCategory.POSITION_UNAVAILABLE, AccuracyCategory.NOT_APPLICABLE);
        }
        BigDecimal distance = request.measurementStatus() == MeasurementStatus.MEASURED
                ? haversineMeters(store.latitude(), store.longitude(),
                        request.latitude(), request.longitude())
                : null;
        ResultCategory result = judgeDistance(
                distance, request.accuracyMeters(), request.measuredAt(), now,
                request.measurementStatus(), request.integrityStatus());
        AccuracyCategory accuracy = request.accuracyMeters() == null
                ? AccuracyCategory.NOT_APPLICABLE
                : request.accuracyMeters().compareTo(MAX_ACCURACY_METERS) <= 0
                        ? AccuracyCategory.ACCEPTABLE
                        : AccuracyCategory.INSUFFICIENT;
        return new Judgment(result, accuracy);
    }

    static ResultCategory judgeDistance(BigDecimal distance, BigDecimal accuracy,
            Instant measuredAt, Instant now, MeasurementStatus measurementStatus,
            IntegrityStatus integrityStatus) {
        if (integrityStatus == IntegrityStatus.MANIPULATION_SUSPECTED) {
            return ResultCategory.MANIPULATION_SUSPECTED;
        }
        if (measurementStatus == MeasurementStatus.PERMISSION_DENIED) {
            return ResultCategory.PERMISSION_DENIED;
        }
        if (measurementStatus == MeasurementStatus.POSITION_UNAVAILABLE) {
            return ResultCategory.POSITION_UNAVAILABLE;
        }
        if (measuredAt.isBefore(now.minus(MAX_MEASUREMENT_AGE))
                || measuredAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            return ResultCategory.MEASUREMENT_STALE;
        }
        if (accuracy.compareTo(MAX_ACCURACY_METERS) > 0) {
            return ResultCategory.ACCURACY_INSUFFICIENT;
        }
        return distance.add(accuracy).compareTo(RADIUS_METERS) <= 0
                ? ResultCategory.VERIFIED
                : ResultCategory.OUTSIDE_RADIUS;
    }

    private static BigDecimal haversineMeters(BigDecimal lat1, BigDecimal lon1,
            BigDecimal lat2, BigDecimal lon2) {
        double firstLatitude = Math.toRadians(lat1.doubleValue());
        double secondLatitude = Math.toRadians(lat2.doubleValue());
        double latitudeDelta = secondLatitude - firstLatitude;
        double longitudeDelta = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        double angularDistance = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return new BigDecimal(EARTH_RADIUS_METERS * angularDistance, MathContext.DECIMAL64);
    }

    record Judgment(ResultCategory resultCategory, AccuracyCategory accuracyCategory) { }
}
