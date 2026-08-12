package com.miriyum.domain.schedule.dto.contract;

import java.util.List;

/** 검색 도메인이 소비하는 매장 공개 일정 projection이다. */
public record PublicStoreSchedules(
        List<PublicOperatingDay> operatingHours,
        List<PublicReservationDay> reservationTimeSlots
) {
    public PublicStoreSchedules {
        operatingHours = List.copyOf(operatingHours);
        reservationTimeSlots = List.copyOf(reservationTimeSlots);
    }

    public static PublicStoreSchedules empty() {
        return new PublicStoreSchedules(List.of(), List.of());
    }
}
