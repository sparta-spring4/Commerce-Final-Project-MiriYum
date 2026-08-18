package com.miriyum.domain.reservation.waiting.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 개인·팀 식별자 없이 Waiting 원장이 제공하는 대시보드 통계 공개 계약이다. */
public record WaitingAnalyticsSnapshot(
        long storeId,
        LocalDate businessDate,
        Instant asOf,
        long waitingTeams,
        long calledTeams,
        long waitingPeople,
        long calledPeople,
        Long longestWaitSeconds,
        long confirmedNoShowTeams,
        String inputCheckpoint,
        Instant dataThrough,
        long sourceVersion,
        boolean corrected
) {
    public WaitingAnalyticsSnapshot {
        if (storeId <= 0 || businessDate == null || asOf == null) {
            throw new IllegalArgumentException("store, businessDate and asOf are required");
        }
        if (waitingTeams < 0 || calledTeams < 0
                || waitingPeople < 0 || calledPeople < 0
                || confirmedNoShowTeams < 0 || sourceVersion <= 0
                || (longestWaitSeconds != null && longestWaitSeconds < 0)) {
            throw new IllegalArgumentException("waiting analytics values are invalid");
        }
        if (inputCheckpoint == null || !inputCheckpoint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("opaque SHA-256 inputCheckpoint is required");
        }
        if (dataThrough != null && dataThrough.isAfter(asOf)) {
            throw new IllegalArgumentException("dataThrough must not be after asOf");
        }
    }
}
