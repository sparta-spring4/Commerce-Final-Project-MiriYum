package com.miriyum.domain.notification.dto.response;

import java.util.List;

public record NotificationHistoryPageResponse(
        List<NotificationHistoryItemResponse> items,
        boolean hasNext,
        String nextCursor
) {
}
