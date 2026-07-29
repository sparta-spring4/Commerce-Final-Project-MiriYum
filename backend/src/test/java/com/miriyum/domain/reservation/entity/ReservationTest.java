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

    @Test
    @DisplayName("confirms a reservation from an approved transaction snapshot")
    void confirmsReservationFromApprovedSnapshot() {
        Reservation reservation = createConfirmedReservation();

        assertThat(reservation.getConsumerAccountId()).isEqualTo(11L);
        assertThat(reservation.getStoreId()).isEqualTo(22L);
        assertThat(reservation.getStoreNameSnapshot()).isEqualTo("Miri Yum Restaurant");
        assertThat(reservation.getServiceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(reservation.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(reservation.getEndTime()).isEqualTo(LocalTime.of(19, 30));
        assertThat(reservation.getParty().totalCount()).isEqualTo(3);
        assertThat(reservation.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(reservation.getReservationPolicyVersion()).isEqualTo(5L);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("cancels a confirmed reservation")
    void cancelsConfirmedReservation() {
        Reservation reservation = createConfirmedReservation();

        reservation.cancel(TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("fulfills a confirmed reservation")
    void fulfillsConfirmedReservation() {
        Reservation reservation = createConfirmedReservation();

        reservation.fulfill(TERMINATED_AT);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("rejects transition from cancelled to fulfilled")
    void rejectsTransitionFromCancelledToFulfilled() {
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getFulfilledAt()).isNull();
    }

    @Test
    @DisplayName("rejects transition from fulfilled to cancelled")
    void rejectsTransitionFromFulfilledToCancelled() {
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("rejects repeated cancellation")
    void rejectsRepeatedCancellation() {
        Reservation reservation = createConfirmedReservation();
        reservation.cancel(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.cancel(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("rejects repeated fulfillment")
    void rejectsRepeatedFulfillment() {
        Reservation reservation = createConfirmedReservation();
        reservation.fulfill(TERMINATED_AT);

        ServiceException exception = catchThrowableOfType(
                () -> reservation.fulfill(TERMINATED_AT.plusSeconds(60)),
                ServiceException.class
        );

        assertThat(exception.getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.FULFILLED);
        assertThat(reservation.getFulfilledAt()).isEqualTo(TERMINATED_AT);
    }

    @Test
    @DisplayName("rejects non-positive owner IDs")
    void rejectsNonPositiveOwnerId() {
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(0L, 22L, "Miri Yum Restaurant", 3L, 5L, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 0L, "Miri Yum Restaurant", 3L, 5L, CREATED_AT));
    }

    @Test
    @DisplayName("rejects a blank store name snapshot")
    void rejectsBlankStoreNameSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, " ", 3L, 5L, CREATED_AT));
    }

    @Test
    @DisplayName("rejects non-positive policy versions")
    void rejectsNonPositivePolicyVersion() {
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, "Miri Yum Restaurant", 0L, 5L, CREATED_AT));
        assertThatIllegalArgumentException().isThrownBy(() -> createReservation(11L, 22L, "Miri Yum Restaurant", 3L, 0L, CREATED_AT));
    }

    @Test
    @DisplayName("rejects a missing required transaction snapshot")
    void rejectsMissingRequiredSnapshot() {
        assertThatIllegalArgumentException().isThrownBy(() -> Reservation.confirm(
                11L, 22L, "Miri Yum Restaurant", null, LocalTime.of(18, 0), LocalTime.of(19, 30),
                PartyComposition.of(2, 1, 0), 3L, 5L, CREATED_AT
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
                capacityPolicyVersion, reservationPolicyVersion, createdAt
        );
    }
}
