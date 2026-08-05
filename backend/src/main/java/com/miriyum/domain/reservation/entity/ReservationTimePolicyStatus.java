package com.miriyum.domain.reservation.entity;

/**
 * Reservation 도메인이 소유하는 시간 정책 버전의 수명 주기 상태다.
 */
public enum ReservationTimePolicyStatus {
    DRAFT,
    SCHEDULED,
    ACTIVE,
    RETIRED,
    ACTIVATION_FAILED
}
