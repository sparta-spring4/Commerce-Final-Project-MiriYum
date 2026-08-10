package com.miriyum.domain.pickup.service;

import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Store 시간대에서 픽업 제공 구간의 현재 거래 가능 여부를 판단한다. */
@Component
final class PickupIntervalTimePolicy {

    private final Clock clock;

    PickupIntervalTimePolicy(Clock clock) {
        this.clock = clock;
    }

    boolean isOpen(String timeZoneId, LocalDate endDate, LocalTime endTime) {
        return resolveUnambiguousInstant(timeZoneId, endDate, endTime)
                .filter(endInstant -> clock.instant().isBefore(endInstant))
                .isPresent();
    }

    Optional<Instant> resolveUnambiguousInstant(
            String timeZoneId,
            LocalDate date,
            LocalTime time
    ) {
        ZoneId zoneId = ZoneId.of(timeZoneId);
        LocalDateTime localDateTime = LocalDateTime.of(date, time);
        List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(localDateTime);
        if (offsets.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(localDateTime.toInstant(offsets.getFirst()));
    }

    void requireOpen(String timeZoneId, LocalDate endDate, LocalTime endTime) {
        if (!isOpen(timeZoneId, endDate, endTime)) {
            throw new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE);
        }
    }
}
