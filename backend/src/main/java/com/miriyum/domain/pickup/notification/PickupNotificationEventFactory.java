package com.miriyum.domain.pickup.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PickupNotificationEventFactory {

    public NotificationSourceEventV1 confirmed(
            PickupReservation pickup,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(pickup, PickupStatus.CONFIRMED);
        return event(
                pickup,
                "confirmed",
                NotificationPurpose.PICKUP_RESERVATION_CONFIRMED,
                1L,
                occurredAt,
                correlationId
        );
    }

    public NotificationSourceEventV1 cancelled(
            PickupReservation pickup,
            Instant occurredAt,
            String correlationId
    ) {
        requireState(pickup, PickupStatus.CANCELLED);
        return event(
                pickup,
                "cancelled",
                NotificationPurpose.PICKUP_RESERVATION_CANCELLED,
                2L,
                occurredAt,
                correlationId
        );
    }

    private static NotificationSourceEventV1 event(
            PickupReservation pickup,
            String eventName,
            NotificationPurpose purpose,
            long resourceVersion,
            Instant occurredAt,
            String correlationId
    ) {
        Long pickupId = Objects.requireNonNull(
                pickup.getId(), "persisted pickup reservation id is required"
        );
        OffsetDateTime normalizedOccurredAt = OffsetDateTime.ofInstant(
                Objects.requireNonNull(occurredAt, "occurredAt must not be null")
                        .truncatedTo(ChronoUnit.SECONDS),
                ZoneOffset.UTC
        );
        return new NotificationSourceEventV1(
                "pickup-reservation:" + pickupId + ":" + eventName,
                NotificationSourceDomain.PICKUP,
                purpose,
                Long.toString(pickup.getConsumerAccountId()),
                1L,
                NotificationResourceType.PICKUP_RESERVATION,
                Long.toString(pickupId),
                resourceVersion,
                pickup.getStatus().name(),
                normalizedOccurredAt,
                normalizedOccurredAt,
                null,
                null,
                correlationId
        );
    }

    private static void requireState(PickupReservation pickup, PickupStatus expected) {
        Objects.requireNonNull(pickup, "pickup must not be null");
        if (pickup.getStatus() != expected) {
            throw new IllegalArgumentException("pickup state does not match event purpose");
        }
    }
}
