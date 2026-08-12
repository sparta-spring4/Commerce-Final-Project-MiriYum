package com.miriyum.domain.notification.dto.source;

import com.miriyum.domain.notification.entity.NotificationActionAvailability;
import com.miriyum.domain.notification.entity.NotificationActionType;
import com.miriyum.domain.notification.entity.NotificationResourceType;
import java.time.OffsetDateTime;
import java.util.Objects;

public record NotificationSourceContextV1(
        NotificationSourceReadResult result,
        long resourceVersion,
        long recipientRelationVersion,
        String sourceState,
        String storeDisplayName,
        String resourceDisplayName,
        OffsetDateTime scheduledAt,
        OffsetDateTime expiresAt,
        NotificationActionType actionType,
        NotificationResourceType actionResourceType,
        String actionResourceId,
        NotificationActionAvailability actionAvailability
) {

    public NotificationSourceContextV1 {
        Objects.requireNonNull(result, "result must not be null");
        boolean anyAction = actionType != null || actionResourceType != null
                || actionResourceId != null || actionAvailability != null;
        boolean completeAction = actionType != null && actionResourceType != null
                && actionResourceId != null && actionAvailability != null;
        if (anyAction && !completeAction) {
            throw new IllegalArgumentException("action tuple must be entirely null or non-null");
        }
        if (completeAction && !actionResourceId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("actionResourceId must be a positive public ID");
        }
        if (completeAction && !isAllowedAction(actionType, actionResourceType)) {
            throw new IllegalArgumentException("action type and resource type are not compatible");
        }
    }

    private static boolean isAllowedAction(
            NotificationActionType actionType,
            NotificationResourceType resourceType
    ) {
        return switch (actionType) {
            case RESERVATION_DETAIL -> resourceType == NotificationResourceType.RESERVATION;
            case PICKUP_RESERVATION_DETAIL ->
                    resourceType == NotificationResourceType.PICKUP_RESERVATION;
            case MENU_SUBSTITUTION_REVIEW ->
                    resourceType == NotificationResourceType.MENU_SUBSTITUTION_PROPOSAL;
        };
    }
}
