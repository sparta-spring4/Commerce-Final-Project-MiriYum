package com.miriyum.domain.reservation.dto;

import java.util.Set;

/** 한 Store의 확정 일반 예약 영향 projection이다. */
public record StoreReservationImpact(
        long storeId,
        long confirmedCount,
        Set<Long> reservationIds
) {
    public StoreReservationImpact {
        if (storeId <= 0 || confirmedCount < 0 || reservationIds == null
                || confirmedCount != reservationIds.size()) {
            throw new IllegalArgumentException("reservation impact is invalid");
        }
        reservationIds = Set.copyOf(reservationIds);
    }
}
