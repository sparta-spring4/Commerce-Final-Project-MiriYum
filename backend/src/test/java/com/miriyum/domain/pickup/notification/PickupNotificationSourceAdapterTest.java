package com.miriyum.domain.pickup.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;
import com.miriyum.domain.notification.dto.source.NotificationActionType;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class PickupNotificationSourceAdapterTest {

    @Mock
    private PickupReservationRepository repository;

    @Test
    void returnsFoundForMatchingConfirmedPickupAndRecipient() {
        PickupReservation pickup = PickupNotificationEventFactoryTest.confirmedPickup();
        given(repository.findById(77L)).willReturn(Optional.of(pickup));
        PickupNotificationSourceAdapter adapter = new PickupNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(context.resourceVersion()).isEqualTo(1L);
        assertThat(context.recipientRelationVersion()).isEqualTo(1L);
        assertThat(context.sourceState()).isEqualTo("CONFIRMED");
        assertThat(context.storeDisplayName()).isEqualTo("미리윰 식당");
        assertThat(context.actionType())
                .isEqualTo(NotificationActionType.PICKUP_RESERVATION_DETAIL);
        assertThat(context.actionResourceType())
                .isEqualTo(NotificationResourceType.PICKUP_RESERVATION);
        assertThat(context.actionResourceId()).isEqualTo("77");
        assertThat(context.actionAvailability())
                .isEqualTo(NotificationActionAvailability.AVAILABLE);
    }

    @Test
    void returnsFoundForMatchingCancellationWithExpiredAction() {
        PickupReservation pickup = PickupNotificationEventFactoryTest.confirmedPickup();
        pickup.cancelByConsumer(null, PickupNotificationEventFactoryTest.TERMINAL_AT);
        given(repository.findById(77L)).willReturn(Optional.of(pickup));
        PickupNotificationSourceAdapter adapter = new PickupNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 2L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.FOUND);
        assertThat(context.resourceVersion()).isEqualTo(2L);
        assertThat(context.sourceState()).isEqualTo("CANCELLED");
        assertThat(context.actionAvailability()).isEqualTo(NotificationActionAvailability.EXPIRED);
    }

    @Test
    void returnsSupersededWhenVersionChangedOrPickupCompleted() {
        PickupReservation pickup = PickupNotificationEventFactoryTest.confirmedPickup();
        pickup.pickUp(PickupNotificationEventFactoryTest.TERMINAL_AT);
        given(repository.findById(77L)).willReturn(Optional.of(pickup));
        PickupNotificationSourceAdapter adapter = new PickupNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result()).isEqualTo(NotificationSourceReadResult.SUPERSEDED);
        assertThat(context.resourceVersion()).isEqualTo(2L);
        assertThat(context.sourceState()).isEqualTo("PICKED_UP");
        assertThat(context.actionAvailability())
                .isEqualTo(NotificationActionAvailability.SUPERSEDED);
    }

    @Test
    void returnsNotEligibleForMalformedMissingOrDifferentRecipient() {
        PickupReservation pickup = PickupNotificationEventFactoryTest.confirmedPickup();
        given(repository.findById(77L)).willReturn(Optional.empty());
        given(repository.findById(78L)).willReturn(Optional.of(pickup));
        PickupNotificationSourceAdapter adapter = new PickupNotificationSourceAdapter(repository);

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
        PickupNotificationSourceAdapter adapter = new PickupNotificationSourceAdapter(repository);

        NotificationSourceContextV1 context = adapter.readContext("77", 1L, "11");

        assertThat(context.result())
                .isEqualTo(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        assertThat(context.actionType()).isNull();
    }
}
