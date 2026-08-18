package com.miriyum.domain.reservation.dto.contract;

import java.time.Instant;
import java.time.LocalDate;

/** 개인정보나 원본 식별자를 포함하지 않는 Reservation 소유 통계 공개 계약이다. */
public record ReservationAnalyticsSnapshot(
        long storeId,
        LocalDate businessDate,
        Instant asOf,
        long todayReservationTeams,
        long reservedPeopleUnits,
        long offeredPeopleUnits,
        long reservedTeamUnits,
        long offeredTeamUnits,
        long cancelledTeams,
        long everConfirmedTeams,
        long confirmedNoShowTeams,
        String inputCheckpoint,
        Instant dataThrough,
        long sourceVersion,
        boolean corrected
) {
    public ReservationAnalyticsSnapshot {
        if (storeId <= 0 || businessDate == null || asOf == null) {
            throw new IllegalArgumentException("store, businessDate and asOf are required");
        }
        if (todayReservationTeams < 0
                || reservedPeopleUnits < 0
                || offeredPeopleUnits < 0
                || reservedTeamUnits < 0
                || offeredTeamUnits < 0
                || cancelledTeams < 0
                || everConfirmedTeams < 0
                || confirmedNoShowTeams < 0
                || sourceVersion <= 0) {
            throw new IllegalArgumentException("analytics counts and sourceVersion are invalid");
        }
        if (cancelledTeams > everConfirmedTeams
                || todayReservationTeams > everConfirmedTeams
                || confirmedNoShowTeams > everConfirmedTeams) {
            throw new IllegalArgumentException("reservation lifecycle counts are inconsistent");
        }
        if (inputCheckpoint == null || !inputCheckpoint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("opaque SHA-256 inputCheckpoint is required");
        }
        if (dataThrough != null && dataThrough.isAfter(asOf)) {
            throw new IllegalArgumentException("dataThrough must not be after asOf");
        }
    }
}
