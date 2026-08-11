package com.miriyum.domain.search.dto.publicapi;

/**
 * 공개 매장 검색에서 예약 가용성 판정 상태를 나타낸다.
 */
public enum ReservationAvailability {
    /** 예약 조건이 제공되지 않아 가용성 판정을 수행하지 않았다. */
    NOT_REQUESTED,

    /** 완전한 예약 조건으로 판정했으며 예약 가능한 매장이다. */
    AVAILABLE,

    /** 완전한 예약 조건으로 판정했으며 예약할 수 없는 매장이다. */
    UNAVAILABLE
}
