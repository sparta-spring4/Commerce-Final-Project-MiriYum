package com.miriyum.domain.reservation.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.dto.request.ReservationSearchAvailabilityCondition;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationSearchAvailabilityPublicContractTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 17);

    @Test
    @DisplayName("검색 가용성 조건은 날짜만 필수이고 시간과 인원은 독립적으로 생략할 수 있다")
    void acceptsIndependentlyOptionalTimeAndPartySize() {
        ReservationSearchAvailabilityCondition dateOnly =
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        null,
                        false
                );
        ReservationSearchAvailabilityCondition dateAndTime =
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        LocalTime.of(18, 30),
                        null,
                        null,
                        false
                );
        ReservationSearchAvailabilityCondition dateAndParty =
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        2,
                        false
                );

        assertThat(dateOnly.startTime()).isNull();
        assertThat(dateOnly.partySize()).isNull();
        assertThat(dateAndTime.startTime()).isEqualTo(LocalTime.of(18, 30));
        assertThat(dateAndTime.partySize()).isNull();
        assertThat(dateAndParty.startTime()).isNull();
        assertThat(dateAndParty.partySize()).isEqualTo(2);
    }

    @Test
    @DisplayName("날짜가 없거나 offset만 있거나 인원 범위가 잘못되면 조건 생성을 거부한다")
    void rejectsStructurallyInvalidPartialConditions() {
        assertThatThrownBy(() -> new ReservationSearchAvailabilityCondition(
                null, null, null, null, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationSearchAvailabilityCondition(
                SERVICE_DATE, null, ZoneOffset.ofHours(9), null, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationSearchAvailabilityCondition(
                SERVICE_DATE, null, null, 0, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationSearchAvailabilityCondition(
                SERVICE_DATE, null, null, 101, false
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
