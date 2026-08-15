package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReservationHoldWarningTaskTest {

    private static final Instant HOLD_CREATED_AT =
            Instant.parse("2026-08-12T01:00:00.123456Z");
    private static final Instant HOLD_EXPIRES_AT =
            HOLD_CREATED_AT.plus(Duration.ofMinutes(10));

    @Test
    void schedulesOneDurableWarningObligationTwoMinutesBeforeExpiration() {
        ReservationHoldWarningTask task = ReservationHoldWarningTask.schedule(
                11L,
                HOLD_CREATED_AT,
                HOLD_EXPIRES_AT
        );

        assertThat(task.getReservationHoldId()).isEqualTo(11L);
        assertThat(task.getCreatedAt()).isEqualTo(HOLD_CREATED_AT);
        assertThat(task.getWarningDueAt())
                .isEqualTo(HOLD_EXPIRES_AT.minus(Duration.ofMinutes(2)));
    }

    @Test
    void rejectsMissingOrNonTenMinuteHoldWindow() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldWarningTask.schedule(0L, HOLD_CREATED_AT, HOLD_EXPIRES_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldWarningTask.schedule(11L, null, HOLD_EXPIRES_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldWarningTask.schedule(11L, HOLD_CREATED_AT, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldWarningTask.schedule(
                        11L, HOLD_CREATED_AT, HOLD_EXPIRES_AT.minusNanos(1)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldWarningTask.schedule(
                        11L, HOLD_CREATED_AT, HOLD_EXPIRES_AT.plusNanos(1)));
    }

    @Test
    void doesNotOwnDeliveryChannelPayloadOrRetryState() {
        assertThat(Arrays.stream(ReservationHoldWarningTask.class.getDeclaredFields())
                .map(field -> field.getName()))
                .containsExactlyInAnyOrder("id", "reservationHoldId", "createdAt", "warningDueAt");
    }
}
