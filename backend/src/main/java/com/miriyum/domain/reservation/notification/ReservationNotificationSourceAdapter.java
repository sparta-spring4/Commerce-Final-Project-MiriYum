package com.miriyum.domain.reservation.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.entity.NotificationActionType;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class ReservationNotificationSourceAdapter implements ReservationNotificationSource {

    private final ReservationRepository repository;

    public ReservationNotificationSourceAdapter(ReservationRepository repository) {
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
                    .map(reservation -> context(
                            reservation, expectedVersion, parsedRecipientId
                    ))
                    .orElseGet(() -> empty(NotificationSourceReadResult.NOT_ELIGIBLE));
        } catch (DataAccessException unavailable) {
            return empty(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static NotificationSourceContextV1 context(
            Reservation reservation,
            long expectedVersion,
            long recipientAccountId
    ) {
        if (reservation.getConsumerAccountId() != recipientAccountId) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        long currentVersion = reservation.getStatus() == ReservationStatus.CONFIRMED ? 1L : 2L;
        boolean superseded = reservation.getStatus() == ReservationStatus.FULFILLED
                || currentVersion != expectedVersion;
        NotificationSourceReadResult result = superseded
                ? NotificationSourceReadResult.SUPERSEDED
                : NotificationSourceReadResult.FOUND;
        NotificationActionAvailability availability = superseded
                ? NotificationActionAvailability.SUPERSEDED
                : reservation.getStatus() == ReservationStatus.CANCELLED
                        ? NotificationActionAvailability.EXPIRED
                        : NotificationActionAvailability.AVAILABLE;
        return new NotificationSourceContextV1(
                result,
                currentVersion,
                1L,
                reservation.getStatus().name(),
                reservation.getStoreNameSnapshot(),
                null,
                null,
                null,
                NotificationActionType.RESERVATION_DETAIL,
                NotificationResourceType.RESERVATION,
                Long.toString(reservation.getId()),
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
