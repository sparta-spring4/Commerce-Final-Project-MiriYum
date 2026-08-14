package com.miriyum.domain.pickup.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;
import com.miriyum.domain.notification.dto.source.NotificationActionType;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.port.PickupNotificationSource;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class PickupNotificationSourceAdapter implements PickupNotificationSource {

    private final PickupReservationRepository repository;

    public PickupNotificationSourceAdapter(PickupReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public NotificationSourceContextV1 readContext(
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        Long parsedResourceId = parsePublicId(resourceId);
        Long parsedRecipientId = parsePublicId(recipientAccountId);
        if (parsedResourceId == null || parsedRecipientId == null || expectedVersion <= 0) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        try {
            return repository.findById(parsedResourceId)
                    .map(pickup -> context(pickup, expectedVersion, parsedRecipientId))
                    .orElseGet(() -> empty(NotificationSourceReadResult.NOT_ELIGIBLE));
        } catch (DataAccessException unavailable) {
            return empty(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static NotificationSourceContextV1 context(
            PickupReservation pickup,
            long expectedVersion,
            long recipientAccountId
    ) {
        if (pickup.getConsumerAccountId() != recipientAccountId) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        long currentVersion = pickup.getStatus() == PickupStatus.CONFIRMED ? 1L : 2L;
        boolean superseded = pickup.getStatus() == PickupStatus.PICKED_UP
                || currentVersion != expectedVersion;
        NotificationSourceReadResult result = superseded
                ? NotificationSourceReadResult.SUPERSEDED
                : NotificationSourceReadResult.FOUND;
        NotificationActionAvailability availability = superseded
                ? NotificationActionAvailability.SUPERSEDED
                : pickup.getStatus() == PickupStatus.CANCELLED
                        ? NotificationActionAvailability.EXPIRED
                        : NotificationActionAvailability.AVAILABLE;
        return new NotificationSourceContextV1(
                result,
                currentVersion,
                1L,
                pickup.getStatus().name(),
                pickup.getStoreNameSnapshot(),
                null,
                null,
                null,
                NotificationActionType.PICKUP_RESERVATION_DETAIL,
                NotificationResourceType.PICKUP_RESERVATION,
                Long.toString(pickup.getId()),
                availability
        );
    }

    private static NotificationSourceContextV1 empty(NotificationSourceReadResult result) {
        return new NotificationSourceContextV1(
                result,
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Long parsePublicId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
