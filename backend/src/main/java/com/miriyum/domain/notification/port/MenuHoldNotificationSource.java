package com.miriyum.domain.notification.port;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.entity.NotificationResourceType;

public interface MenuHoldNotificationSource {
    NotificationSourceContextV1 readContext(
            NotificationResourceType resourceType,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    );
}
