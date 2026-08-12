package com.miriyum.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.entity.NotificationActionType;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.port.MenuHoldNotificationSource;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationProductionDependencyTest {

    @Test
    void publicRecordingAndSourcePortsExposeOnlyNotificationOwnedTypes() {
        List<Class<?>> boundaries = List.of(
                NotificationTaskRecorder.class,
                ReservationNotificationSource.class,
                MenuHoldNotificationSource.class,
                PickupNotificationSource.class
        );

        assertThat(boundaries).allSatisfy(boundary ->
                assertThat(boundary.getDeclaredMethods()).allSatisfy(this::usesNotificationTypesOnly));
    }

    @Test
    void contextRejectsPartiallyPopulatedOrMismatchedActions() {
        assertThatThrownBy(() -> context(
                NotificationActionType.PICKUP_RESERVATION_DETAIL,
                null,
                "21",
                NotificationActionAvailability.AVAILABLE
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action tuple");

        assertThatThrownBy(() -> context(
                NotificationActionType.PICKUP_RESERVATION_DETAIL,
                NotificationResourceType.RESERVATION,
                "21",
                NotificationActionAvailability.AVAILABLE
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compatible");
    }

    private void usesNotificationTypesOnly(Method method) {
        assertThat(method.getReturnType().getPackageName())
                .startsWith("com.miriyum.domain.notification");
        assertThat(method.getParameterTypes())
                .allMatch(type -> type.isPrimitive()
                        || type.getPackageName().equals("java.lang")
                        || type.getPackageName().startsWith("com.miriyum.domain.notification"));
    }

    private static NotificationSourceContextV1 context(
            NotificationActionType actionType,
            NotificationResourceType actionResourceType,
            String actionResourceId,
            NotificationActionAvailability availability
    ) {
        return new NotificationSourceContextV1(
                NotificationSourceReadResult.FOUND,
                3L,
                7L,
                "CONFIRMED",
                "미리윰",
                null,
                null,
                null,
                actionType,
                actionResourceType,
                actionResourceId,
                availability
        );
    }
}
