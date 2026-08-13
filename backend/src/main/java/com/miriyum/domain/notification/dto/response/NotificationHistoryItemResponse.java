package com.miriyum.domain.notification.dto.response;

import com.miriyum.domain.notification.entity.NotificationPurpose;
import java.time.OffsetDateTime;

public record NotificationHistoryItemResponse(
        String notificationId,
        NotificationPurpose purpose,
        String title,
        NotificationResourceResponse resource,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt,
        OffsetDateTime deliveredAt,
        NotificationActionResponse action
) {
}
