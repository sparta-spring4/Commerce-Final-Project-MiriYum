package com.miriyum.domain.notification.dto.response;

/** 소비자 본인에게 공개된 현재 미확인 알림 개수다. */
public record NotificationUnreadCountResponse(long unreadCount) {
}
