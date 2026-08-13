package com.miriyum.domain.notification.dto.response;

import com.miriyum.domain.notification.entity.NotificationResourceType;

public record NotificationResourceResponse(
        NotificationResourceType type,
        String id
) {
}
