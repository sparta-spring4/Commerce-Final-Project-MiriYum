package com.miriyum.domain.reservation.notification;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationSourceReadResult;
import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;
import com.miriyum.domain.notification.dto.source.NotificationActionType;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.notification.dto.source.NotificationResourceType;
import com.miriyum.domain.notification.port.ReservationNotificationSource;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import java.util.Optional;
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
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(
                purpose, resourceId, expectedVersion, recipientAccountId, false);
    }

    @Override
    public NotificationSourceContextV1 readContextForDelivery(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(
                purpose, resourceId, expectedVersion, recipientAccountId, true);
    }

    private NotificationSourceContextV1 readContext(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId,
            boolean delivery
    ) {
        Long parsedResourceId = parsePublicId(resourceId);
        Long parsedRecipientId = parsePublicId(recipientAccountId);
        if (!isReservationPurpose(purpose)
                || parsedResourceId == null
                || parsedRecipientId == null
                || expectedVersion <= 0) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        try {
            Optional<Reservation> found = delivery
                    ? repository.findByIdAndConsumerAccountIdForUpdate(
                            parsedResourceId, parsedRecipientId)
                    : repository.findById(parsedResourceId);
            return found
                    .map(reservation -> context(
                            purpose, reservation, expectedVersion, parsedRecipientId
                    ))
                    .orElseGet(() -> empty(NotificationSourceReadResult.NOT_ELIGIBLE));
        } catch (DataAccessException unavailable) {
            if (delivery) {
                throw unavailable;
            }
            return empty(NotificationSourceReadResult.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static NotificationSourceContextV1 context(
            NotificationPurpose purpose,
            Reservation reservation,
            long expectedVersion,
            long recipientAccountId
    ) {
        if (reservation.getConsumerAccountId() != recipientAccountId) {
            return empty(NotificationSourceReadResult.NOT_ELIGIBLE);
        }
        long currentVersion = reservation.getStatus() == ReservationStatus.CONFIRMED ? 1L : 2L;
        boolean terminalPurpose = purpose == NotificationPurpose.RESERVATION_VISIT_COMPLETED
                || purpose == NotificationPurpose.RESERVATION_NO_SHOW;
        ReservationStatus purposeState = expectedState(purpose);
        boolean superseded = purposeState == null
                || currentVersion != expectedVersion
                || reservation.getStatus() != purposeState;
        NotificationSourceReadResult result = superseded
                ? NotificationSourceReadResult.SUPERSEDED
                : NotificationSourceReadResult.FOUND;
        NotificationActionAvailability availability = terminalPurpose
                ? null
                : superseded
                        ? NotificationActionAvailability.SUPERSEDED
                        : reservation.getStatus() == ReservationStatus.CONFIRMED
                                ? NotificationActionAvailability.AVAILABLE
                                : NotificationActionAvailability.EXPIRED;
        return new NotificationSourceContextV1(
                result,
                currentVersion,
                1L,
                reservation.getStatus().name(),
                reservation.getStoreNameSnapshot(),
                null,
                null,
                null,
                terminalPurpose ? null : NotificationActionType.RESERVATION_DETAIL,
                terminalPurpose ? null : NotificationResourceType.RESERVATION,
                terminalPurpose ? null : Long.toString(reservation.getId()),
                availability
        );
    }

    private static boolean isReservationPurpose(NotificationPurpose purpose) {
        return purpose != null && switch (purpose) {
            case RESERVATION_CONFIRMED,
                    RESERVATION_CHANGED,
                    RESERVATION_REJECTED,
                    RESERVATION_CANCELLED,
                    RESERVATION_EXPIRED,
                    RESERVATION_VISIT_REMINDER,
                    RESERVATION_COORDINATION_REQUIRED,
                    RESERVATION_VISIT_COMPLETED,
                    RESERVATION_NO_SHOW -> true;
            default -> false;
        };
    }

    private static ReservationStatus expectedState(NotificationPurpose purpose) {
        return switch (purpose) {
            case RESERVATION_CONFIRMED,
                    RESERVATION_CHANGED,
                    RESERVATION_VISIT_REMINDER,
                    RESERVATION_COORDINATION_REQUIRED -> ReservationStatus.CONFIRMED;
            case RESERVATION_REJECTED,
                    RESERVATION_EXPIRED -> null;
            case RESERVATION_CANCELLED -> ReservationStatus.CANCELLED;
            case RESERVATION_VISIT_COMPLETED -> ReservationStatus.FULFILLED;
            case RESERVATION_NO_SHOW -> ReservationStatus.NO_SHOW;
            default -> throw new IllegalArgumentException("reservation purpose is required");
        };
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
