package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationStatusTest {

    @Test
    @DisplayName("1차 MVP 예약 상태는 확정·취소·방문 완료만 사용한다")
    void containsOnlyApprovedMvpStatuses() {
        // when & then
        assertThat(ReservationStatus.values())
                .containsExactly(
                        ReservationStatus.CONFIRMED,
                        ReservationStatus.CANCELLED,
                        ReservationStatus.FULFILLED
                );
    }
}
