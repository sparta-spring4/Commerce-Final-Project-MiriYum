package com.miriyum.domain.notification.dto.source;

import com.miriyum.domain.notification.entity.NotificationPurpose;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import com.miriyum.domain.notification.entity.NotificationSourceDomain;
import java.time.OffsetDateTime;
import java.util.Objects;

public record NotificationSourceEventV1(
        String sourceEventId,
        NotificationSourceDomain sourceDomain,
        NotificationPurpose purpose,
        String recipientAccountId,
        long recipientRelationVersion,
        NotificationResourceType resourceType,
        String resourceId,
        long resourceVersion,
        String sourceState,
        OffsetDateTime occurredAt,
        OffsetDateTime scheduledAt,
        OffsetDateTime expiresAt,
        Long timingPolicyVersion,
        String correlationId
) {

    public static final String CONTRACT_VERSION = "notification-source-event-v1";

    public NotificationSourceEventV1 {
        requireText(sourceEventId, 100, "sourceEventId");
        Objects.requireNonNull(sourceDomain, "sourceDomain must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        requirePublicId(recipientAccountId, "recipientAccountId");
        requirePositive(recipientRelationVersion, "recipientRelationVersion");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        requireOwnedPurpose(sourceDomain, purpose, resourceType);
        requirePublicId(resourceId, "resourceId");
        requirePositive(resourceVersion, "resourceVersion");
        requireText(sourceState, 64, "sourceState");
        requireWholeSecond(occurredAt, "occurredAt");
        requireWholeSecond(scheduledAt, "scheduledAt");
        if (expiresAt != null) {
            requireWholeSecond(expiresAt, "expiresAt");
        }
        if (scheduledAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("scheduledAt must not be before occurredAt");
        }
        if (expiresAt != null && expiresAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("expiresAt must not be before occurredAt");
        }
        if (timingPolicyVersion != null && timingPolicyVersion <= 0) {
            throw new IllegalArgumentException("timingPolicyVersion must be positive");
        }
        if (purpose == NotificationPurpose.RESERVATION_VISIT_REMINDER
                && (expiresAt == null || timingPolicyVersion == null
                || !scheduledAt.isBefore(expiresAt))) {
            throw new IllegalArgumentException(
                    "scheduled notification requires scheduledAt before expiresAt and timingPolicyVersion"
            );
        }
        requireText(correlationId, 100, "correlationId");
    }

    private static void requireText(String value, int max, String fieldName) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and at most " + max);
        }
    }

    private static void requirePublicId(String value, String fieldName) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(fieldName + " must be a positive public ID");
        }
        try {
            if (Long.parseLong(value) <= 0) {
                throw new IllegalArgumentException(fieldName + " must be a positive public ID");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must fit signed 64-bit", exception);
        }
    }

    private static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void requireOwnedPurpose(
            NotificationSourceDomain sourceDomain,
            NotificationPurpose purpose,
            NotificationResourceType resourceType
    ) {
        boolean allowed = switch (purpose) {
            case RESERVATION_CONFIRMED,
                    RESERVATION_CHANGED,
                    RESERVATION_REJECTED,
                    RESERVATION_CANCELLED,
                    RESERVATION_EXPIRED,
                    RESERVATION_VISIT_REMINDER,
                    RESERVATION_COORDINATION_REQUIRED ->
                    sourceDomain == NotificationSourceDomain.RESERVATION
                            && resourceType == NotificationResourceType.RESERVATION;
            case PICKUP_RESERVATION_CONFIRMED,
                    PICKUP_RESERVATION_CANCELLED ->
                    sourceDomain == NotificationSourceDomain.PICKUP
                            && resourceType == NotificationResourceType.PICKUP_RESERVATION;
            case MENU_HOLD_FULFILLMENT_AT_RISK ->
                    sourceDomain == NotificationSourceDomain.MENU_HOLD
                            && resourceType == NotificationResourceType.MENU_HOLD;
            case MENU_SUBSTITUTION_PROPOSED,
                    MENU_SUBSTITUTION_ACCEPTED,
                    MENU_SUBSTITUTION_REJECTED,
                    MENU_SUBSTITUTION_EXPIRED ->
                    sourceDomain == NotificationSourceDomain.MENU_HOLD
                            && resourceType == NotificationResourceType.MENU_SUBSTITUTION_PROPOSAL;
        };
        if (!allowed) {
            throw new IllegalArgumentException("purpose is not owned by sourceDomain and resourceType");
        }
    }

    private static void requireWholeSecond(OffsetDateTime value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.getNano() != 0) {
            throw new IllegalArgumentException(fieldName + " must have whole-second precision");
        }
    }
}
