package com.miriyum.domain.reservation.entity;

/**
 * 예약 취소 정책 스냅샷에 저장하는 양수 BIGINT 버전 값이다.
 */
public record ReservationCancellationPolicyVersion(long value) {

    public ReservationCancellationPolicyVersion {
        if (value <= 0) {
            throw new IllegalArgumentException("value must be positive");
        }
    }
}
