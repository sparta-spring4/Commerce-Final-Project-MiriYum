package com.miriyum.domain.notification.port;

import com.miriyum.domain.notification.dto.source.NotificationSourceContextV1;

/** Notification이 Waiting 원장의 최신 목적별 상태를 조회하는 공개 경계다. */
public interface WaitingNotificationSource {

    NotificationSourceContextV1 readContext(
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    );

    /** 전달 완료와 Waiting 상태 전이를 직렬화해야 하는 worker 전용 조회다. */
    default NotificationSourceContextV1 readContextForDelivery(
            String resourceId,
            long expectedVersion,
            String recipientAccountId
    ) {
        return readContext(resourceId, expectedVersion, recipientAccountId);
    }
}
