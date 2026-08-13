package com.miriyum.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.miriyum.domain.notification.config.NotificationSettings;
import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import com.miriyum.domain.notification.port.MenuHoldNotificationSource;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
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
        NotificationSourceContextV1 context = mock(NotificationSourceContextV1.class);
        given(pickup.readContext("21", 3L, "11")).willReturn(context);
        NotificationSourceRegistry registry = new NotificationSourceRegistry(
                Optional.of(reservation), Optional.of(menuHold), Optional.of(pickup));

        assertThat(registry.readContext(
                NotificationSourceDomain.PICKUP,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isSameAs(context);
        verifyNoInteractions(menuHold);
        assertThatThrownBy(() -> registry.readContext(
                NotificationSourceDomain.MENU_HOLD,
                NotificationResourceType.PICKUP_RESERVATION,
                21L,
                3L,
                11L
        )).isInstanceOf(IllegalStateException.class);
    }
}
