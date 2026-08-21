package com.miriyum.domain.reservation.waiting.dto;

import java.time.Instant;
import java.util.List;

/** 소비자 웨이팅 이력 공개 조회의 요청·응답 계약을 모은다. */
public final class WaitingConsumerHistoryContracts {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAXIMUM_PAGE_SIZE = 50;

    private WaitingConsumerHistoryContracts() {
    }

    /** 현재·종료 상태 묶음을 선택하는 공개 필터다. */
    public enum Scope {
        ALL,
        CURRENT,
        TERMINAL
    }

    /** Waiting 원 도메인이 소비자 이력에 공개하는 상태 어휘다. */
    public enum HistoryStatus {
        WAITING,
        CALLED,
        ARRIVED,
        CHECKED_IN,
        CANCELLED,
        NO_SHOW,
        CLOSED_BY_STORE,
        RESERVATION_CONVERTING,
        RESERVATION_CONVERTED
    }

    /** 공개 query parameter를 정규화한 query service 입력이다. */
    public record HistoryQuery(Scope scope, String cursor, int size) {

        public HistoryQuery {
            if (scope == null || size < 1 || size > MAXIMUM_PAGE_SIZE) {
                throw new IllegalArgumentException("history query must be valid");
            }
        }

        public static HistoryQuery from(String rawScope, String cursor, Integer rawSize) {
            Scope scope = rawScope == null ? Scope.ALL : Scope.valueOf(rawScope);
            return of(scope, cursor, rawSize);
        }

        public static HistoryQuery of(Scope scope, String cursor, Integer rawSize) {
            Scope normalizedScope = scope == null ? Scope.ALL : scope;
            int size = rawSize == null ? DEFAULT_PAGE_SIZE : rawSize;
            return new HistoryQuery(normalizedScope, cursor, size);
        }
    }

    /** 내부 계정·위치·감사·원장 정보를 제외한 단일 웨이팅 이력이다. */
    public record HistoryItem(
            String waitingTeamId,
            String storeId,
            HistoryStatus status,
            Instant registeredAt,
            Instant calledAt,
            Instant terminatedAt
    ) {
    }

    /** 고정 정렬된 소비자 웨이팅 이력 keyset page다. */
    public record HistoryPage(List<HistoryItem> items, String nextCursor) {
        public HistoryPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
