package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;

/**
 * 저장된 정책 버전과 예약 스냅샷으로 취소 가능 여부를 판정한다.
 */
public class ReservationCancellationPolicyEvaluator {

    private final ReservationCancellationPolicyRegistry registry;

    public ReservationCancellationPolicyEvaluator(
            ReservationCancellationPolicyRegistry registry) {
        this.registry = requireNonNull(registry, "registry");
    }

    public ReservationCancellationDecision evaluate(
            Long storedVersion,
            ReservationCancellationActorType actor,
            ReservationStatus status,
            Instant startAt,
            Instant requestedAt
    ) {
        ReservationStatus validatedStatus = requireNonNull(status, "status");
        if (validatedStatus != ReservationStatus.CONFIRMED) {
            return ReservationCancellationDecision.REJECTED_INVALID_STATE;
        }

        requireNonNull(actor, "actor");
        requireNonNull(startAt, "startAt");
        requireNonNull(requestedAt, "requestedAt");
        if (registry.findByStoredVersion(storedVersion).isEmpty()) {
            return ReservationCancellationDecision.REJECTED_BY_POLICY;
        }
        return ReservationCancellationDecision.ALLOWED;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
