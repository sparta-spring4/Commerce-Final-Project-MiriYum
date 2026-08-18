package com.miriyum.domain.reservation.waiting.dto;

import java.time.LocalDate;

/** 중앙 시각 기준 소비자 웨이팅 접수 가능 상태다. */
public record WaitingReceptionAvailability(
        String storeId,
        boolean accepting,
        LocalDate businessDate
) {

    public static WaitingReceptionAvailability closed(long storeId) {
        return new WaitingReceptionAvailability(Long.toString(storeId), false, null);
    }

    public static WaitingReceptionAvailability open(long storeId, LocalDate businessDate) {
        return new WaitingReceptionAvailability(Long.toString(storeId), true, businessDate);
    }
}
