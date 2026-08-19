package com.miriyum.global.sse;

/** 공개 SSE stream이 결속되는 인증 audience와 업무 event 이름이다. */
public enum SseAudience {
    NOTIFICATION_CONSUMER("notifications.changed"),
    WAITING_CONSUMER("waiting.changed"),
    WAITING_STORE_OPERATOR("waiting.changed");

    private final String eventName;

    SseAudience(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
