package com.miriyum.global.sse;

import java.time.LocalDate;

/** Valkey에 원문 식별자를 싣지 않도록 HMAC routing key로 바꿀 내부 wake-up 대상이다. */
public record SseWakeUpTarget(Kind kind, Long accountId, Long storeId, LocalDate businessDate) {

    public enum Kind {
        NOTIFICATION_ACCOUNT,
        WAITING_ACCOUNT,
        WAITING_STORE_DATE,
        WAITING_STORE
    }

    public SseWakeUpTarget {
        if (kind == null) {
            throw new IllegalArgumentException("wake-up target kind is required");
        }
        switch (kind) {
            case NOTIFICATION_ACCOUNT, WAITING_ACCOUNT -> {
                requirePositive(accountId, "accountId");
                requireAbsent(storeId, businessDate);
            }
            case WAITING_STORE_DATE -> {
                requirePositive(storeId, "storeId");
                if (businessDate == null || accountId != null) {
                    throw new IllegalArgumentException("store/date target fields are invalid");
                }
            }
            case WAITING_STORE -> {
                requirePositive(storeId, "storeId");
                requireAbsent(accountId, businessDate);
            }
        }
    }

    public static SseWakeUpTarget notificationAccount(long accountId) {
        return new SseWakeUpTarget(Kind.NOTIFICATION_ACCOUNT, accountId, null, null);
    }

    public static SseWakeUpTarget waitingAccount(long accountId) {
        return new SseWakeUpTarget(Kind.WAITING_ACCOUNT, accountId, null, null);
    }

    public static SseWakeUpTarget waitingStoreDate(long storeId, LocalDate businessDate) {
        return new SseWakeUpTarget(Kind.WAITING_STORE_DATE, null, storeId, businessDate);
    }

    public static SseWakeUpTarget waitingStore(long storeId) {
        return new SseWakeUpTarget(Kind.WAITING_STORE, null, storeId, null);
    }

    String canonicalValue() {
        return String.join("\n",
                kind.name(),
                accountId == null ? "" : accountId.toString(),
                storeId == null ? "" : storeId.toString(),
                businessDate == null ? "" : businessDate.toString());
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireAbsent(Object first, Object second) {
        if (first != null || second != null) {
            throw new IllegalArgumentException("wake-up target contains unrelated fields");
        }
    }
}
