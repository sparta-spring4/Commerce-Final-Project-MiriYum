package com.miriyum.domain.pickup.service;

import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** Store 시간대에서 픽업 제공 구간의 현재 거래 가능 여부를 판단한다. */
@Component
final class PickupIntervalTimePolicy {

    private final Clock clock;

    PickupIntervalTimePolicy(Clock clock) {
        this.clock = clock;
    }

    boolean isOpen(String timeZoneId, LocalDate endDate, LocalTime endTime) {
        ZoneId zoneId = ZoneId.of(timeZoneId);
        LocalDateTime localEnd = LocalDateTime.of(endDate, endTime);
        List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(localEnd);
        return offsets.size() == 1
                && clock.instant().isBefore(localEnd.toInstant(offsets.getFirst()));
    }

    void requireOpen(String timeZoneId, LocalDate endDate, LocalTime endTime) {
        if (!isOpen(timeZoneId, endDate, endTime)) {
            throw new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE);
        }
    }
}
