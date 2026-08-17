package com.miriyum.domain.reservation.entity;

/**
 * 지속하고 공개하는 일반 예약 종결 상태다.
 */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED,
    FULFILLED,
    NO_SHOW
}
