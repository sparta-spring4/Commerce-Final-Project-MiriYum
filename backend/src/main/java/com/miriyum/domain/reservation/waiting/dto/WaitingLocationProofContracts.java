package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.AccuracyCategory;
import com.miriyum.domain.reservation.waiting.entity.WaitingLocationProofSession.ResultCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 소비자 위치 판정의 일시적 입력과 최소 응답 계약이다. */
public final class WaitingLocationProofContracts {

    private WaitingLocationProofContracts() { }

    public enum MeasurementStatus { MEASURED, PERMISSION_DENIED, POSITION_UNAVAILABLE }
    public enum IntegrityStatus { CLEAR, MANIPULATION_SUSPECTED }

    public record Request(
            MeasurementStatus measurementStatus,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal accuracyMeters,
            Instant measuredAt,
            IntegrityStatus integrityStatus
    ) {
        public Request {
            if (measurementStatus == null || integrityStatus == null) {
                throw new IllegalArgumentException("measurement and integrity status are required");
            }
            boolean hasAllRaw = latitude != null && longitude != null
                    && accuracyMeters != null && measuredAt != null;
            boolean hasAnyRaw = latitude != null || longitude != null
                    || accuracyMeters != null || measuredAt != null;
            if (measurementStatus == MeasurementStatus.MEASURED && !hasAllRaw) {
                throw new IllegalArgumentException("measured location requires every measurement field");
            }
            if (measurementStatus != MeasurementStatus.MEASURED && hasAnyRaw) {
                throw new IllegalArgumentException("failed measurement must not include location fields");
            }
            if (hasAllRaw) {
                if (latitude.compareTo(new BigDecimal("-90")) < 0
                        || latitude.compareTo(new BigDecimal("90")) > 0
                        || longitude.compareTo(new BigDecimal("-180")) < 0
                        || longitude.compareTo(new BigDecimal("180")) > 0
                        || accuracyMeters.signum() <= 0) {
                    throw new IllegalArgumentException("location measurement is out of range");
                }
            }
        }
    }

    public record Snapshot(
            UUID proofSessionId,
            ResultCategory resultCategory,
            AccuracyCategory accuracyCategory,
            String policyVersion,
            long storeCoordinateVersion,
            Instant issuedAt,
            Instant judgedAt,
            Instant expiresAt
    ) { }
}
