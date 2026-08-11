package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PickupIntervalTimePolicyTest {

    private static final LocalDate SEOUL_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime SEOUL_END = LocalTime.of(13, 0);

    @Test
    void keepsIntervalOpenImmediatelyBeforeItsStoreLocalEnd() {
        PickupIntervalTimePolicy policy = policyAt("2026-08-10T03:59:59Z");

        assertThat(policy.isOpen("Asia/Seoul", SEOUL_DATE, SEOUL_END)).isTrue();
    }

    @Test
    void closesIntervalExactlyAtItsStoreLocalEnd() {
        PickupIntervalTimePolicy policy = policyAt("2026-08-10T04:00:00Z");

        assertThat(policy.isOpen("Asia/Seoul", SEOUL_DATE, SEOUL_END)).isFalse();
        assertThatThrownBy(() -> policy.requireOpen(
                "Asia/Seoul", SEOUL_DATE, SEOUL_END))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE));
    }

    @Test
    void closesIntervalAfterItsStoreLocalEnd() {
        PickupIntervalTimePolicy policy = policyAt("2026-08-10T04:00:01Z");

        assertThat(policy.isOpen("Asia/Seoul", SEOUL_DATE, SEOUL_END)).isFalse();
    }

    @Test
    void rejectsDstGapAndOverlapEndTimesWithoutChoosingAnOffset() {
        PickupIntervalTimePolicy policy = policyAt("2026-01-01T00:00:00Z");

        assertThat(policy.isOpen(
                "America/New_York", LocalDate.of(2026, 3, 8), LocalTime.of(2, 30)))
                .isFalse();
        assertThat(policy.isOpen(
                "America/New_York", LocalDate.of(2026, 11, 1), LocalTime.of(1, 30)))
                .isFalse();
    }

    private static PickupIntervalTimePolicy policyAt(String instant) {
        return new PickupIntervalTimePolicy(Clock.fixed(
                Instant.parse(instant), ZoneOffset.UTC));
    }
}
