package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.notification.config.NotificationSettings;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.notification.port.MenuHoldNotificationSource;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import com.miriyum.domain.notification.port.WaitingNotificationSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationDeliveryServiceTest {

    @Test
    void workerDoesNotClaimTasksWhenRuntimePolicyIsMissing() {
        NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
        NotificationTaskWorker worker = new NotificationTaskWorker(
                new NotificationSettings(
                        false, null, null, null, null, null, null, null, null, null),
                deliveryService
        );

        assertThat(worker.deliverDueBatch()).isZero();
        verifyNoInteractions(deliveryService);
    }

    @Test
    void workerUsesOneValidatedPolicySnapshotForTheBatch() {
        NotificationDeliveryService deliveryService = mock(NotificationDeliveryService.class);
        given(deliveryService.deliverDueBatch(any())).willReturn(2);
        NotificationTaskWorker worker = new NotificationTaskWorker(
                new NotificationSettings(
                        true,
                        "notification-worker-v1",
                        "worker-a",
                        10,
                        30_000L,
                        3,
                        5_000L,
                        60_000L,
                        1_000L,
                        1_000L
                ),
                deliveryService
        );

        assertThat(worker.deliverDueBatch()).isEqualTo(2);
    }

    @Test
    void pickupResourceNeverFallsBackToMenuHoldSource() {
        ReservationNotificationSource reservation =
                mock(ReservationNotificationSource.class);
        MenuHoldNotificationSource menuHold = mock(MenuHoldNotificationSource.class);
        PickupNotificationSource pickup = mock(PickupNotificationSource.class);
        WaitingNotificationSource waiting = mock(WaitingNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        given(pickup.readContext("21", 3L, "11")).willReturn(context);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.of(menuHold), Optional.of(pickup),
                Optional.of(waiting));

        assertThat(registry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isSameAs(context);
        verifyNoInteractions(menuHold);
        assertThatThrownBy(() -> registry.readContext(
                NotificationSourceDomain.MENU_HOLD,
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitingTeamUsesOnlyTheWaitingSource() {
        ReservationNotificationSource reservation = mock(ReservationNotificationSource.class);
        MenuHoldNotificationSource menuHold = mock(MenuHoldNotificationSource.class);
        PickupNotificationSource pickup = mock(PickupNotificationSource.class);
        WaitingNotificationSource waiting = mock(WaitingNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        NotificationSourceContextV1 deliveryContext = mock(NotificationSourceContextV1.class);
        given(waiting.readContext(
                NotificationPurpose.WAITING_CALLED, "31", 4L, "11"))
                .willReturn(context);
        given(waiting.readContextForDelivery(
                NotificationPurpose.WAITING_CALLED, "31", 4L, "11"))
                .willReturn(deliveryContext);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.of(menuHold), Optional.of(pickup),
                Optional.of(waiting));

        assertThat(registry.readContext(
                NotificationSourceDomain.WAITING,
                NotificationPurpose.WAITING_CALLED,
                NotificationResourceType.WAITING_TEAM,
                31L,
                4L,
                11L
        )).isSameAs(context);
        assertThat(registry.readContextForDelivery(
                NotificationSourceDomain.WAITING,
                NotificationPurpose.WAITING_CALLED,
                NotificationResourceType.WAITING_TEAM,
                31L,
                4L,
                11L
        )).isSameAs(deliveryContext);
        verifyNoInteractions(reservation, menuHold, pickup);
    }

    @Test
    void reservationSourceReceivesTheNotificationPurpose() {
        ReservationNotificationSource reservation = mock(ReservationNotificationSource.class);
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        NotificationSourceContextV1 deliveryContext = mock(NotificationSourceContextV1.class);
        given(reservation.readContext(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11"))
                .willReturn(context);
        given(reservation.readContextForDelivery(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11"))
                .willReturn(deliveryContext);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(registry.readContext(
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                NotificationResourceType.RESERVATION,
                77L,
                2L,
                11L
        )).isSameAs(context);
        assertThat(registry.readContextForDelivery(
                NotificationSourceDomain.RESERVATION,
                NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                NotificationResourceType.RESERVATION,
                77L,
                2L,
                11L
        )).isSameAs(deliveryContext);
        verify(reservation).readContext(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11");
        verify(reservation).readContextForDelivery(
                NotificationPurpose.RESERVATION_VISIT_COMPLETED, "77", 2L, "11");
    }
}
