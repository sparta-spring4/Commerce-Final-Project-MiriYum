package com.miriyum.domain.reservation.dto.response;

import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ReservationTimePolicyResponse(
        String storeId,
        long version,
        int slotInterval,
        int serviceDuration,
        int turnoverDuration,
        ReservationTimePolicyStatus status,
        OffsetDateTime effectiveAt
) {

    public static ReservationTimePolicyResponse from(ReservationTimePolicyVersion policy) {
        if (policy == null) {
            throw new IllegalArgumentException("reservation time policy is required");
        }
        return new ReservationTimePolicyResponse(
                Long.toString(policy.getStoreId()),
                policy.getVersionNumber(),
                policy.getSlotIntervalMinutes(),
                policy.getServiceDurationMinutes(),
                policy.getTurnoverDurationMinutes(),
                policy.getStatus(),
                policy.getEffectiveAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(policy.getEffectiveAt(), ZoneOffset.UTC)
        );
    }
}
