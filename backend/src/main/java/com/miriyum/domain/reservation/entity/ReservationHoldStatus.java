package com.miriyum.domain.reservation.entity;

/** 고도화 임시 선점의 영속 상태 계약이다. */
public enum ReservationHoldStatus {
    ACTIVE,
    RECONCILIATION_REQUIRED,
    CONFIRMED,
    RELEASED,
    EXPIRED;

    /**
     * 이 상태로 성공 전이한 뒤 수용량 점유를 반환해야 하는지 나타낸다.
     *
     * @return 해제 또는 만료 상태이면 {@code true}
     */
    public boolean requiresCapacityRelease() {
        return this == RELEASED || this == EXPIRED;
    }
}
