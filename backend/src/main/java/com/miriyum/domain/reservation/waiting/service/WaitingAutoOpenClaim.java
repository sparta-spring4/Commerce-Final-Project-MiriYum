package com.miriyum.domain.reservation.waiting.service;

import java.time.Instant;
import java.time.LocalDate;

public record WaitingAutoOpenClaim(
        long jobId,
        long storeId,
        String businessIntervalKey,
        LocalDate businessDate,
        Instant intervalStartsAt,
        Instant intervalEndsAt,
        long expectedSettingsVersion,
        int expectedAdvanceOpenMinutes,
        String leaseOwner,
        long fencingToken,
        int attemptCount,
        Instant leaseUntil
) {
}
