package com.miriyum.domain.reservation.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.entity.NotificationActionType;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class ReservationNotificationSourceAdapterTest {

    @Mock
    private ReservationRepository repository;

    @Test
    void returnsFoundForMatchingConfirmedReservationAndRecipient() {
        Reservation reservation = ReservationNotificationEventFactoryTest.confirmedReservation();
        given(repository.findById(77L)).willReturn(Optional.of(reservation));
        ReservationNotificationSourceAdapter adapter =
                new ReservationNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(context.resourceVersion()).isEqualTo(1L);
        assertThat(context.recipientRelationVersion()).isEqualTo(1L);
        assertThat(context.sourceState()).isEqualTo("CONFIRMED");
        assertThat(context.storeDisplayName()).isEqualTo("미리윰 식당");
        assertThat(context.actionType()).isEqualTo(NotificationActionType.RESERVATION_DETAIL);
        assertThat(context.actionResourceType()).isEqualTo(NotificationResourceType.RESERVATION);
        assertThat(context.actionResourceId()).isEqualTo("77");
        assertThat(context.actionAvailability())
                .isEqualTo(NotificationActionAvailability.AVAILABLE);
    }

    @Test
    void returnsFoundForMatchingCancellationWithExpiredAction() {
        Reservation reservation = ReservationNotificationEventFactoryTest.confirmedReservation();
        reservation.cancel(ReservationNotificationEventFactoryTest.TERMINAL_AT);
        given(repository.findById(77L)).willReturn(Optional.of(reservation));
        ReservationNotificationSourceAdapter adapter =
                new ReservationNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 2L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(context.resourceVersion()).isEqualTo(2L);
        assertThat(context.sourceState()).isEqualTo("CANCELLED");
        assertThat(context.actionAvailability()).isEqualTo(NotificationActionAvailability.EXPIRED);
    }

    @Test
    void returnsSupersededWhenVersionChangedOrVisitCompleted() {
        Reservation reservation = ReservationNotificationEventFactoryTest.confirmedReservation();
        reservation.fulfill(ReservationNotificationEventFactoryTest.TERMINAL_AT);
        given(repository.findById(77L)).willReturn(Optional.of(reservation));
        ReservationNotificationSourceAdapter adapter =
                new ReservationNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.SUPERSEDED);
        assertThat(context.resourceVersion()).isEqualTo(2L);
        assertThat(context.sourceState()).isEqualTo("FULFILLED");
        assertThat(context.actionAvailability())
                .isEqualTo(NotificationActionAvailability.SUPERSEDED);
    }

    @Test
    void returnsNotEligibleForMalformedMissingOrDifferentRecipient() {
        Reservation reservation = ReservationNotificationEventFactoryTest.confirmedReservation();
        given(repository.findById(77L)).willReturn(Optional.empty());
        given(repository.findById(78L)).willReturn(Optional.of(reservation));
        ReservationNotificationSourceAdapter adapter =
                new ReservationNotificationSourceAdapter(repository);

        assertThat(adapter.readContext("not-a-public-id", 1L, "11").result())
                .isEqualTo(NotificationSourceReadResult.NOT_ELIGIBLE);
        assertThat(adapter.readContext("77", 1L, "11").result())
                .isEqualTo(NotificationSourceReadResult.NOT_ELIGIBLE);
        assertThat(adapter.readContext("78", 1L, "12").result())
                .isEqualTo(NotificationSourceReadResult.NOT_ELIGIBLE);
    }

    @Test
    void returnsTemporarilyUnavailableForRepositoryFailure() {
        given(repository.findById(77L))
                .willThrow(new DataAccessResourceFailureException("database unavailable"));
        ReservationNotificationSourceAdapter adapter =
                new ReservationNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result())
                .isEqualTo(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        assertThat(context.actionType()).isNull();
    }
}
