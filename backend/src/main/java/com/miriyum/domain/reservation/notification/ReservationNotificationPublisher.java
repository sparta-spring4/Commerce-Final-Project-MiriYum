package com.miriyum.domain.reservation.notification;

import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.service.NotificationTaskRecorder;
import com.miriyum.domain.reservation.entity.Reservation;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class ReservationNotificationPublisher {

    private final ReservationNotificationEventFactory eventFactory;
    private final NotificationTaskRecorder taskRecorder;

    public ReservationNotificationPublisher(
            ReservationNotificationEventFactory eventFactory,
            NotificationTaskRecorder taskRecorder
    ) {
        this.eventFactory = eventFactory;
        this.taskRecorder = taskRecorder;
    }

    public NotificationTaskReceipt recordConfirmed(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.confirmed(
                reservation, occurredAt, correlationId
        ));
    }

    public NotificationTaskReceipt recordCancelled(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.cancelled(
                reservation, occurredAt, correlationId
        ));
    }

    public NotificationTaskReceipt recordVisitCompleted(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.visitCompleted(
                reservation, occurredAt, correlationId
        ));
    }

    public NotificationTaskReceipt recordNoShow(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        return taskRecorder.record(eventFactory.noShow(
                reservation, occurredAt, correlationId
        ));
    }
}
