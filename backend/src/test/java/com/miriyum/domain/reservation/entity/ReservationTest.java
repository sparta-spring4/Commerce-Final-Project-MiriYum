package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
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
        assertThat(reservation.getStartAt()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
        assertThat(reservation.getServiceEndAt())
                .isEqualTo(Instant.parse("2026-08-01T10:30:00Z"));
        assertThat(reservation.getOccupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-01T10:45:00Z"));
        assertThat(reservation.getTimeZoneId()).isEqualTo("Asia/Seoul");
        assertThat(reservation.getParty().totalCount()).isEqualTo(3);
        assertThat(reservation.getContactSnapshot().getNotificationTargetReference())
                .isEqualTo(NOTIFICATION_TARGET_REFERENCE);
        assertThat(reservation.getContactSnapshot().isContactAvailableAtConfirmation()).isTrue();
        assertThat(reservation.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(reservation.getCancellationPolicyVersion()).isEqualTo(1L);
        assertThat(reservation.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
        assertThat(reservation.getNoShowAt()).isNull();
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
        assertThat(reservation.getNoShowAt()).isNull();
    }

    @Test
    @DisplayName("확정 예약을 노쇼 상태로 종결한다")
    void marksConfirmedReservationNoShow() {
        // given
        Reservation reservation = createConfirmedReservation();

        // when
        reservation.markNoShow(TERMINATED_AT);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getNoShowAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
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
    @DisplayName("예약 생성 이전 시각으로 노쇼 확정할 수 없다")
    void rejectsNoShowBeforeCreation() {
        Reservation reservation = createConfirmedReservation();

        assertThatIllegalArgumentException().isThrownBy(() ->
                reservation.markNoShow(Instant.parse("2026-08-01T00:59:59Z"))
        );

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getNoShowAt()).isNull();
    }

    @Test
    @DisplayName("노쇼 예약은 다른 종결 상태로 바꿀 수 없다")
    void rejectsTransitionFromNoShow() {
        Reservation reservation = createConfirmedReservation();
        reservation.markNoShow(TERMINATED_AT);

        ServiceException fulfillmentFailure = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60))
        );
        ServiceException cancellationFailure = catchThrowableOfType(
                ServiceException.class,
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60))
        );

        assertThat(fulfillmentFailure.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(cancellationFailure.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getNoShowAt()).isEqualTo(TERMINATED_AT);
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
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(
                0L, 22L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(
                11L, 0L, "Miri Yum Restaurant", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT));
    }

    @Test
    @DisplayName("비어 있는 매장명 스냅샷을 거부한다")
    void rejectsBlankStoreNameSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(
                11L, 22L, " ", 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT));
    }

    @Test
    @DisplayName("양수가 아닌 정책 버전을 거부한다")
    void rejectsNonPositivePolicyVersion() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(
                11L, 22L, "Miri Yum Restaurant", 0L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT));
    }

    @Test
    @DisplayName("취소 정책 버전 값은 양수여야 한다")
    void rejectsNonPositiveCancellationPolicyVersionValue() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ReservationCancellationPolicyVersion(0L)
        );
    }

    @Test
    @DisplayName("취소 정책 버전이 없으면 예약을 확정할 수 없다")
    void rejectsMissingCancellationPolicyVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(
                11L, 22L, "Miri Yum Restaurant", 3L, null, CREATED_AT
        ));
    }

    @Test
    @DisplayName("취소 정책 버전을 명시하는 예약 확정 API만 제공한다")
    void exposesOnlyCancellationPolicyVersionConfirmApi() {
        assertThat(Arrays.stream(Reservation.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("confirm")
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()))
                .toList())
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes()).containsExactly(
                        Long.class,
                        Long.class,
                        String.class,
                        ReservationTimeSnapshot.class,
                        PartyComposition.class,
                        ReservationContactSnapshot.class,
                        long.class,
                        ReservationCancellationPolicyVersion.class,
                        Instant.class
                ));
    }

    @Test
    @DisplayName("필수 거래 스냅샷이 없으면 생성할 수 없다")
    void rejectsMissingRequiredSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", null,
                PartyComposition.of(2, 1, 0), contactSnapshot(), 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT
        ));
    }

    @Test
    @DisplayName("연락 스냅샷이 없으면 예약을 확정할 수 없다")
    void rejectsMissingContactSnapshot() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", timeSnapshot(),
                PartyComposition.of(2, 1, 0), null, 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT
        ));
    }

    @Test
    @DisplayName("예약 매장과 시간 정책 스냅샷의 소유 매장이 다르면 생성을 거부한다")
    void rejectsTimeSnapshotOwnedByAnotherStore() {
        ReservationTimePolicyVersion anotherStorePolicy =
                ReservationTimePolicyVersion.createDraft(23L, 5L, 30, 90, 15);
        anotherStorePolicy.activate(
                Instant.parse("2026-07-31T00:00:00Z"),
                "다른 매장 활성 정책"
        );
        ReservationTimeSnapshot anotherStoreSnapshot = ReservationTimeSnapshot.calculate(
                anotherStorePolicy,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );

        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", anotherStoreSnapshot,
                PartyComposition.of(2, 1, 0), contactSnapshot(), 3L,
                new ReservationCancellationPolicyVersion(1L), CREATED_AT
        ));
    }

    private static Reservation createConfirmedReservation() {
        return createReservation(
                11L,
                22L,
                "Miri Yum Restaurant",
                3L,
                new ReservationCancellationPolicyVersion(1L),
                CREATED_AT
        );
    }

    private static Reservation createReservation(
            Long consumerAccountId,
            Long storeId,
            String storeNameSnapshot,
            long capacityPolicyVersion,
            ReservationCancellationPolicyVersion cancellationPolicyVersion,
            Instant createdAt
    ) {
        return Reservation.confirm(
                consumerAccountId, storeId, storeNameSnapshot, timeSnapshot(),
                PartyComposition.of(2, 1, 0), contactSnapshot(), capacityPolicyVersion,
                cancellationPolicyVersion, createdAt
        );
    }

    private static ReservationTimeSnapshot timeSnapshot() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L,
                5L,
                30,
                90,
                15
        );
        policy.activate(Instant.parse("2026-07-31T00:00:00Z"), "활성 정책");
        return ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
    }

    private static ReservationContactSnapshot contactSnapshot() {
        return ReservationContactSnapshot.contactable(NOTIFICATION_TARGET_REFERENCE);
    }
}
