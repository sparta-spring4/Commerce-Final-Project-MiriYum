package com.miriyum.domain.reservation.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReservationTimePolicyDraftRequest(
        @Min(1) @Max(1440) int slotInterval,
        @Min(1) @Max(1440) int serviceDuration,
        @Min(0) @Max(1440) int turnoverDuration
) {

    @AssertTrue(message = "서비스 시간과 전환 시간의 합은 1440분 이하여야 합니다.")
    public boolean isTotalDurationValid() {
        return serviceDuration > 0
                && turnoverDuration >= 0
                && serviceDuration + turnoverDuration <= 1440;
    }
}
