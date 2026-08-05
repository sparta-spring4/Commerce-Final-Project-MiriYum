package com.miriyum.domain.reservation.dto.request;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationAvailabilityConditionTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);

    @Test
    @DisplayName("시작 시각은 분 단위 정밀도만 허용한다")
    void rejectsStartTimeOutsideMinutePrecision() {
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0, 1),
                2
        ));
    }

    @Test
    @DisplayName("일행 인원이 1명에서 100명 범위를 벗어나면 가용성 조건을 거부한다")
    void rejectsPartySizeOutsidePublicRange() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
                0
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> condition(
                LocalTime.of(18, 0),
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
                        null,
                        2,
                        false
                ));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ReservationAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        2,
                        false
                ));
    }

    @Test
    @DisplayName("DST 중복 시각 선택을 위한 offset을 선택적으로 전달할 수 있다")
    void acceptsOptionalStartOffset() {
        new ReservationAvailabilityCondition(
                SERVICE_DATE,
                LocalTime.of(18, 0),
                ZoneOffset.ofHours(9),
                2,
                false
        );
    }

    private static ReservationAvailabilityCondition condition(
            LocalTime startTime,
            int partySize
    ) {
        return new ReservationAvailabilityCondition(
                SERVICE_DATE,
                startTime,
                null,
                partySize,
                false
        );
    }
}
