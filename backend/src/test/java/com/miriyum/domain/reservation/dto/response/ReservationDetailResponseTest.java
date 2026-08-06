package com.miriyum.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.menuhold.dto.MenuHoldItemResult;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReservationDetailResponseTest {

    @Test
    @DisplayName("예약 상세는 고객 시간과 메뉴 거래 스냅샷 순서를 그대로 매핑한다")
    void mapsResolvedReservationAndMenuSnapshotsInServiceOrder() {
        // given
        Reservation reservation = resolvedReservation();
        List<MenuHoldItemResult> snapshots = List.of(
                new MenuHoldItemResult(91L, "아메리카노", 4_500L, 2),
                new MenuHoldItemResult(92L, "바스크 치즈케이크", 7_000L, 1)
        );

        // when
        ReservationDetailResponse response =
                ReservationDetailResponse.from(reservation, snapshots);

        // then
        assertThat(response.reservationId()).isEqualTo("77");
        assertThat(response.storeId()).isEqualTo("22");
        assertThat(response.storeName()).isEqualTo("미리윰 식당");
        assertThat(response.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(response.timeStatus()).isEqualTo(CustomerReservationTimeStatus.RESOLVED);
        assertThat(response.startAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-03T18:00:00+09:00"));
        assertThat(response.serviceEndAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-03T19:00:00+09:00"));
        assertThat(response.timeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(response.party())
                .isEqualTo(new ReservationPartyResponse(2, 1, 0, 3));
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.menuSelections())
                .containsExactly(
                        new ReservationMenuSelectionResponse(
                                "91", "아메리카노", 4_500L, 2),
                        new ReservationMenuSelectionResponse(
                                "92", "바스크 치즈케이크", 7_000L, 1)
                );
        assertThat(response.createdAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-01T09:00:00Z"));
    }

    @Test
    @DisplayName("과거 예약의 실제 시각은 offset을 추측하지 않고 null로 공개한다")
    void mapsLegacyReservationWithoutGuessingOffset() {
        // given
        Reservation reservation = resolvedReservation();
        ReservationTimeSnapshot snapshot = reservation.getTimeSnapshot();
        ReflectionTestUtils.setField(snapshot, "startAt", null);
        ReflectionTestUtils.setField(snapshot, "serviceEndAt", null);
        ReflectionTestUtils.setField(snapshot, "occupancyEndAt", null);
        ReflectionTestUtils.setField(snapshot, "timeZoneId", null);
        ReflectionTestUtils.setField(snapshot, "startOffsetSeconds", null);
        ReflectionTestUtils.setField(snapshot, "serviceEndOffsetSeconds", null);
        ReflectionTestUtils.setField(snapshot, "occupancyEndOffsetSeconds", null);
        ReflectionTestUtils.setField(snapshot, "slotIntervalMinutes", null);
        ReflectionTestUtils.setField(snapshot, "serviceDurationMinutes", null);
        ReflectionTestUtils.setField(snapshot, "turnoverDurationMinutes", null);
        ReflectionTestUtils.setField(snapshot, "reservationTimePolicyStoreId", null);

        // when
        ReservationDetailResponse response =
                ReservationDetailResponse.from(reservation, List.of());

        // then
        assertThat(response.serviceDate()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(response.timeStatus())
                .isEqualTo(CustomerReservationTimeStatus.LEGACY_UNRESOLVED);
        assertThat(response.startAt()).isNull();
        assertThat(response.serviceEndAt()).isNull();
        assertThat(response.timeZoneId()).isNull();
    }

    @Test
    @DisplayName("메뉴 홀드가 없는 예약은 변경할 수 없는 빈 선택 목록을 공개한다")
    void mapsMissingMenuHoldToImmutableEmptySelections() {
        // given
        Reservation reservation = resolvedReservation();

        // when
        ReservationDetailResponse response =
                ReservationDetailResponse.from(reservation, List.of());

        // then
        assertThat(response.menuSelections()).isEmpty();
        assertThatThrownBy(() -> response.menuSelections().add(
                new ReservationMenuSelectionResponse("91", "아메리카노", 4_500L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("예약 상세는 승인된 고객 공개 필드만 제공한다")
    void exposesOnlyCanonicalDetailComponents() {
        // when
        List<String> componentNames = Arrays.stream(
                        ReservationDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // then
        assertThat(componentNames).containsExactly(
                "reservationId",
                "storeId",
                "storeName",
                "serviceDate",
                "timeStatus",
                "startAt",
                "serviceEndAt",
                "timeZoneId",
                "party",
                "status",
                "menuSelections",
                "createdAt"
        );
        assertThat(componentNames).doesNotContain(
                "contactSnapshot",
                "contact",
                "endTime",
                "occupancyEndAt",
                "cancelledBy",
                "cancellationReason"
        );
    }

    private Reservation resolvedReservation() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L,
                4L,
                30,
                60,
                15
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "test policy");
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
        Reservation reservation = Reservation.confirm(
                11L,
                22L,
                "미리윰 식당",
                snapshot,
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                3L,
                new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-01T09:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", 77L);
        return reservation;
    }
}
