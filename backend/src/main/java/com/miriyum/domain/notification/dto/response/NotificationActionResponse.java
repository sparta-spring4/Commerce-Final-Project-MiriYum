package com.miriyum.domain.notification.dto.response;

import com.miriyum.domain.notification.dto.source.NotificationActionAvailability;
import com.miriyum.domain.notification.dto.source.NotificationActionType;
import java.time.OffsetDateTime;

public record NotificationActionResponse(
        NotificationActionType type,
        NotificationResourceResponse resource,
        NotificationActionAvailability availability,
        OffsetDateTime expiresAt
) {
}
