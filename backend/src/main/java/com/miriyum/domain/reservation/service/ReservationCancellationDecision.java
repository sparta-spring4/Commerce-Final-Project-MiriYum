package com.miriyum.domain.reservation.service;

/**
 * 예약 취소 정책의 순수 판정 결과다.
 */
public enum ReservationCancellationDecision {
    ALLOWED,
    REJECTED_INVALID_STATE,
    REJECTED_BY_POLICY
}
