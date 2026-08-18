package com.miriyum.domain.reservation.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.dto.source.NotificationSourceDomain;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReservationNotificationEventFactory {

    public NotificationSourceEventV1 confirmed(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(reservation, ReservationStatus.CONFIRMED);
        return event(
                reservation,
                "confirmed",
                NotificationPurpose.RESERVATION_CONFIRMED,
                1L,
                occurredAt,
                correlationId
        );
    }

    public NotificationSourceEventV1 cancelled(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(reservation, ReservationStatus.CANCELLED);
        return event(
                reservation,
                "cancelled",
                NotificationPurpose.RESERVATION_CANCELLED,
                2L,
                occurredAt,
                correlationId
        );
    }

    public NotificationSourceEventV1 visitCompleted(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(reservation, ReservationStatus.FULFILLED);
        return event(
                reservation,
                "visit-completed",
                NotificationPurpose.RESERVATION_VISIT_COMPLETED,
                2L,
                occurredAt,
                correlationId
        );
    }

    public NotificationSourceEventV1 noShow(
            Reservation reservation,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(reservation, ReservationStatus.NO_SHOW);
        return event(
                reservation,
                "no-show",
                NotificationPurpose.RESERVATION_NO_SHOW,
                2L,
                occurredAt,
                correlationId
        );
    }

    private static NotificationSourceEventV1 event(
            Reservation reservation,
            String eventName,
            NotificationPurpose purpose,
            long resourceVersion,
            Instant occurredAt,
            String correlationId
    ) {
        Long reservationId = Objects.requireNonNull(
                reservation.getId(), "persisted reservation id is required"
        );
        OffsetDateTime normalizedOccurredAt = OffsetDateTime.ofInstant(
                Objects.requireNonNull(occurredAt, "occurredAt must not be null")
                        .truncatedTo(ChronoUnit.SECONDS),
                ZoneOffset.UTC
        );
        return new NotificationSourceEventV1(
                "reservation:" + reservationId + ":" + eventName,
                NotificationSourceDomain.RESERVATION,
                purpose,
                Long.toString(reservation.getConsumerAccountId()),
                1L,
                NotificationResourceType.RESERVATION,
                Long.toString(reservationId),
                resourceVersion,
                reservation.getStatus().name(),
                normalizedOccurredAt,
                normalizedOccurredAt,
                null,
                null,
                correlationId
        );
    }

    private static void requireState(Reservation reservation, ReservationStatus expected) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        if (reservation.getStatus() != expected) {
            throw new IllegalArgumentException("reservation state does not match event purpose");
        }
    }
}
