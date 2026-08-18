package com.miriyum.domain.notification.port;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;

public interface ReservationNotificationSource {
    NotificationSourceContextV1 readContext(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    );

    /** 전달 완료와 Reservation 종결 전이를 직렬화하는 worker 전용 조회다. */
    default NotificationSourceContextV1 readContextForDelivery(
            NotificationPurpose purpose,
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(purpose, resourceId, expectedVersion, recipientAccountId);
    }
}
