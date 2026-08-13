package com.miriyum.domain.pickup.notification;

import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.pickup.entity.PickupReservation;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PickupNotificationPublisher {

    private final PickupNotificationEventFactory eventFactory;
    private final NotificationTaskRecorder taskRecorder;

    public PickupNotificationPublisher(
            PickupNotificationEventFactory eventFactory,
            NotificationTaskRecorder taskRecorder
    ) {
        this.eventFactory = eventFactory;
        this.taskRecorder = taskRecorder;
    }

    public NotificationTaskReceipt recordConfirmed(
            PickupReservation pickup,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.confirmed(
                pickup, occurredAt, correlationId
        ));
    }

    public NotificationTaskReceipt recordCancelled(
            PickupReservation pickup,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.cancelled(
                pickup, occurredAt, correlationId
        ));
    }
}
