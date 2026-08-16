package com.miriyum.domain.reservation.entity;

/** QR grant 발급과 QR 방문 완료 감사 사건을 구분한다. */
public enum ReservationCheckInEventType {
    QR_GRANT_ISSUED,
    QR_CHECK_IN_FULFILLED
}
