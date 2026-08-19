package com.miriyum.global.sse;

/** SSE cursor와 연결을 audience·인증 계정·선택 매장에 결속한다. */
public record SseStreamScope(SseAudience audience, long accountId, Long storeId) {

    public SseStreamScope {
        if (audience == null || accountId <= 0) {
            throw new IllegalArgumentException("SSE scope must contain a positive account");
        }
        if (audience == SseAudience.WAITING_STORE_OPERATOR) {
            if (storeId == null || storeId <= 0) {
                throw new IllegalArgumentException("store SSE scope requires a positive store");
            }
        } else if (storeId != null) {
            throw new IllegalArgumentException("consumer SSE scope must not contain a store");
        }
    }

    public static SseStreamScope notificationConsumer(long accountId) {
        return new SseStreamScope(SseAudience.NOTIFICATION_CONSUMER, accountId, null);
    }

    public static SseStreamScope waitingConsumer(long accountId) {
        return new SseStreamScope(SseAudience.WAITING_CONSUMER, accountId, null);
    }

    public static SseStreamScope waitingStoreOperator(long accountId, long storeId) {
        return new SseStreamScope(SseAudience.WAITING_STORE_OPERATOR, accountId, storeId);
    }
}
