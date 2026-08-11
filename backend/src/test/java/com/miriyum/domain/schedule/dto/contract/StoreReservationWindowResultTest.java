package com.miriyum.domain.schedule.dto.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StoreReservationWindowResultTest {

    @Test
    void acceptingCarriesAValidLocalWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 3, 19, 0);

        StoreReservationWindowResult result =
                StoreReservationWindowResult.accepting(
                        7L,
                        "Asia/Seoul",
                        start,
                        start.plusHours(6));

        assertThat(result.storeId()).isEqualTo(7L);
        assertThat(result.status())
                .isEqualTo(StoreReservationWindowStatus.ACCEPTING);
        assertThat(result.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(result.windowStartAt()).isEqualTo(start);
        assertThat(result.windowEndAt()).isEqualTo(start.plusHours(6));
    }

    @Test
    void acceptingRejectsAZeroLengthWindow() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 3, 19, 0);

        assertThatThrownBy(() -> StoreReservationWindowResult.accepting(
                7L,
                "Asia/Seoul",
                start,
                start))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptingRejectsAnInvalidTimeZone() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 3, 19, 0);

        assertThatThrownBy(() -> StoreReservationWindowResult.accepting(
                7L,
                "not-a-zone",
                start,
                start.plusHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notAcceptingContainsNoFabricatedWindow() {
        StoreReservationWindowResult result =
                StoreReservationWindowResult.notAccepting(7L);

        assertThat(result.status())
                .isEqualTo(StoreReservationWindowStatus.NOT_ACCEPTING);
        assertThat(result.timeZoneId()).isNull();
        assertThat(result.windowStartAt()).isNull();
        assertThat(result.windowEndAt()).isNull();
    }

    @Test
    void resultRejectsANonPositiveStoreId() {
        assertThatThrownBy(() -> StoreReservationWindowResult.notAccepting(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
