package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T01:00:00Z");
    private static final Instant TERMINATED_AT = Instant.parse("2026-08-01T02:00:00Z");
    private static final String NOTIFICATION_TARGET_REFERENCE =
            "consumer:11:channel:primary";

    @Test
    @DisplayName("승인된 거래 스냅샷으로 예약을 즉시 확정한다")
    void confirmsReservationFromApprovedSnapshot() {
        // when
        Reservation reservation = createConfirmedReservation();

        // then
        assertThat(reservation.getConsumerAccountId()).isEqualTo(11L);
        assertThat(reservation.getStoreId()).isEqualTo(22L);
        assertThat(reservation.getStoreNameSnapshot()).isEqualTo("Miri Yum Restaurant");
        assertThat(reservation.getServiceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(reservation.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(reservation.getEndTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(reservation.getParty().totalCount()).isEqualTo(3);
        assertThat(reservation.getContactSnapshot().getNotificationTargetReference())
                .isEqualTo(NOTIFICATION_TARGET_REFERENCE);
        assertThat(reservation.getContactSnapshot().isContactAvailableAtConfirmation()).isTrue();
        assertThat(reservation.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(reservation.getReservationPolicyVersion()).isEqualTo(5L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("확정 예약을 취소 상태로 종결한다")
    void cancelsConfirmedReservation() {
        // given
        Reservation reservation = createConfirmedReservation();

        // when
        reservation.cancel(TERMINATED_AT);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("확정 예약을 방문 완료 상태로 종결한다")
    void fulfillsConfirmedReservation() {
        // given
        Reservation reservation = createConfirmedReservation();

        // when
        reservation.fulfill(TERMINATED_AT);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("예약 생성 이전 시각으로 취소할 수 없다")
    void rejectsCancellationBeforeCreation() {
        // given
        Reservation reservation = createConfirmedReservation();

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                reservation.cancel(Instant.parse("2026-08-01T00:59:59Z"))
        );

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("예약 생성 이전 시각으로 방문 완료할 수 없다")
    void rejectsFulfillmentBeforeCreation() {
        // given
        Reservation reservation = createConfirmedReservation();

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                reservation.fulfill(Instant.parse("2026-08-01T00:59:59Z"))
        );

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("취소된 예약은 방문 완료로 전이할 수 없다")
    void rejectsTransitionFromCancelledToFulfilled() {
        // given
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        // when
        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60))
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("방문 완료된 예약은 취소로 전이할 수 없다")
    void rejectsTransitionFromFulfilledToCancelled() {
        // given
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        // when
        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60))
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("취소된 예약은 취소를 반복할 수 없다")
    void rejectsRepeatedCancellation() {
        // given
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        // when
        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60))
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("방문 완료된 예약은 방문 완료를 반복할 수 없다")
    void rejectsRepeatedFulfillment() {
        // given
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        // when
        ServiceException exception = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60))
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("양수가 아닌 소유 관계 ID를 거부한다")
    void rejectsNonPositiveOwnerId() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(0L, 22L, "Miri Yum Restaurant", 3L, 5L, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 0L, "Miri Yum Restaurant", 3L, 5L, CREATED_AT));
    }

    @Test
    @DisplayName("비어 있는 매장명 스냅샷을 거부한다")
    void rejectsBlankStoreNameSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, " ", 3L, 5L, CREATED_AT));
    }

    @Test
    @DisplayName("양수가 아닌 정책 버전을 거부한다")
    void rejectsNonPositivePolicyVersion() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, "Miri Yum Restaurant", 0L, 5L, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, "Miri Yum Restaurant", 3L, 0L, CREATED_AT));
    }

    @Test
    @DisplayName("필수 거래 스냅샷이 없으면 생성할 수 없다")
    void rejectsMissingRequiredSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", null, LocalTime.of(18, 0), LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0), contactSnapshot(), 3L, 5L, CREATED_AT
        ));
    }

    @Test
    @DisplayName("연락 스냅샷이 없으면 예약을 확정할 수 없다")
    void rejectsMissingContactSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0), LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0), null, 3L, 5L, CREATED_AT
        ));
    }

    @Test
    @DisplayName("종료 시각이 시작 시각보다 늦지 않으면 예약 생성을 거부한다")
    void rejectsNonIncreasingServiceTime() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0), LocalTime.of(18, 0),
                PartyComposition.of(2, 1, 0), contactSnapshot(), 3L, 5L, CREATED_AT
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0), LocalTime.of(17, 30),
                PartyComposition.of(2, 1, 0), contactSnapshot(), 3L, 5L, CREATED_AT
        ));
    }

    private static Reservation createConfirmedReservation() {
        return createReservation(11L, 22L, "Miri Yum Restaurant", 3L, 5L, CREATED_AT);
    }

    private static Reservation createReservation(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            long capacityPolicyVersion,
            long reservationPolicyVersion,
            Instant createdAt
    ) {
        return Reservation.confirm(
                consumerAccountId, storeId, storeNameSnapshot, LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0), LocalTime.of(19, 30), PartyComposition.of(2, 1, 0),
                contactSnapshot(), capacityPolicyVersion, reservationPolicyVersion, createdAt
        );
    }

    private static ReservationContactSnapshot contactSnapshot() {
        return ReservationContactSnapshot.contactable(NOTIFICATION_TARGET_REFERENCE);
    }
}
