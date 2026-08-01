package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationAvailabilityConditionTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);

    @Test
    @DisplayName("종료 시각이 시작 시각보다 늦지 않으면 가용성 조건을 거부한다")
    void rejectsNonIncreasingServiceTime() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
                LocalTime.of(18, 0),
                2
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
                LocalTime.of(17, 30),
                2
        ));
    }

    @Test
    @DisplayName("일행 인원이 1명에서 100명 범위를 벗어나면 가용성 조건을 거부한다")
    void rejectsPartySizeOutsidePublicRange() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                0
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                101
        ));
    }

    @Test
    @DisplayName("업무 날짜와 구간 시각은 모두 필수다")
    void rejectsMissingDateOrTime() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ReservationAvailabilityCondition(
                        null,
                        LocalTime.of(18, 0),
                        LocalTime.of(19, 0),
                        2,
                        false
                ));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ReservationAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        LocalTime.of(19, 0),
                        2,
                        false
                ));
    }

    private static ReservationAvailabilityCondition condition(
            LocalTime startTime,
            LocalTime endTime,
            int partySize
    ) {
        return new ReservationAvailabilityCondition(
                SERVICE_DATE,
                startTime,
                endTime,
                partySize,
                false
        );
    }
}
