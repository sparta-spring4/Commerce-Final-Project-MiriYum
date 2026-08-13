package com.miriyum.domain.notification.dto.source;

public record NotificationTaskReceipt(String notificationId, boolean duplicate) {

    public NotificationTaskReceipt {
        if (notificationId == null || !notificationId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("notificationId must be a positive public ID");
        }
    }
}
