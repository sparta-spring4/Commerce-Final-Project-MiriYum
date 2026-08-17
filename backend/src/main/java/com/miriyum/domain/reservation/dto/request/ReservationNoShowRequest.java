package com.miriyum.domain.reservation.dto.request;

import com.miriyum.domain.reservation.entity.ReservationNoShowReason;
import jakarta.validation.constraints.NotNull;

/** 운영자 노쇼 확정에 필요한 명시적 후보 사유다. */
public record ReservationNoShowRequest(
        @NotNull ReservationNoShowReason reason
) {
}
