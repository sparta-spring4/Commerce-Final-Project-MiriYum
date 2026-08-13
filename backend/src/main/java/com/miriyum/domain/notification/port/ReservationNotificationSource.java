package com.miriyum.domain.notification.port;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;

public interface ReservationNotificationSource {
    NotificationSourceContextV1 readContext(
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    );
}
