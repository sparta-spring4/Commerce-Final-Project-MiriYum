package com.miriyum.domain.reservation.entity;

/** 금전 귀책 확정 전 운영자가 기록하는 필수 노쇼 원인 후보다. */
public enum ReservationNoShowReason {
    USER_CAUSE_CANDIDATE,
    STORE_CAUSE_CANDIDATE,
    PLATFORM_EXTERNAL_CAUSE_CANDIDATE,
    UNCLEAR
}
