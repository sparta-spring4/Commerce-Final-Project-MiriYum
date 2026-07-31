package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class StoreReservationResponseTest {

    @Test
    @DisplayName("예약을 운영자 목록 공개 summary로 변환한다")
    void mapsReservationToStoreSummary() {
        // given
        Reservation reservation = reservation(33L);

        // when
        StoreReservationSummaryResponse response =
                StoreReservationSummaryResponse.from(reservation);

        // then
        assertThat(response.reservationId()).isEqualTo("33");
        assertThat(response.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(response.startTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(response.totalPartySize()).isEqualTo(3);
        assertThat(response.status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("운영자 예약 페이지를 입력 순서와 공통 페이지 메타데이터로 변환한다")
    void mapsStoreReservationPageAndMetadata() {
        // given
        Page<Reservation> reservations = new PageImpl<>(
                List.of(reservation(102L), reservation(101L)),
                PageRequest.of(1, 2),
                5
        );

        // when
        StoreReservationPageResponse response =
                StoreReservationPageResponse.from(reservations);

        // then
        assertThat(response.items())
                .extracting(StoreReservationSummaryResponse::reservationId)
                .containsExactly("102", "101");
        assertThat(response.page().number()).isEqualTo(1);
        assertThat(response.page().size()).isEqualTo(2);
        assertThat(response.page().totalElements()).isEqualTo(5);
        assertThat(response.page().totalPages()).isEqualTo(3);
        assertThat(response.page().hasNext()).isTrue();
    }

    @Test
    @DisplayName("빈 운영자 예약 페이지는 빈 배열과 0개 메타데이터로 변환한다")
    void mapsEmptyStoreReservationPage() {
        // given
        Page<Reservation> reservations = Page.empty(PageRequest.of(0, 20));

        // when
        StoreReservationPageResponse response =
                StoreReservationPageResponse.from(reservations);

        // then
        assertThat(response.items()).isEmpty();
        assertThat(response.page().number()).isZero();
        assertThat(response.page().size()).isEqualTo(20);
        assertThat(response.page().totalElements()).isZero();
        assertThat(response.page().totalPages()).isZero();
        assertThat(response.page().hasNext()).isFalse();
    }

    private Reservation reservation(Long reservationId) {
        Reservation reservation = Reservation.confirm(
                11L,
                22L,
                "미리윰 식당",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(19, 0),
                PartyComposition.of(2, 1, 0),
                3L,
                4L,
                Instant.parse("2026-07-31T09:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", reservationId);
        return reservation;
    }
}
