package com.miriyum.domain.reservation.entity;

/** 고도화 임시 선점의 영속 상태 계약이다. */
public enum ReservationHoldStatus {
    ACTIVE,
    RECONCILIATION_REQUIRED,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
