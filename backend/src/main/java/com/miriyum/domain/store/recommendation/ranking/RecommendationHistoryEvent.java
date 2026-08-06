package com.miriyum.domain.store.recommendation.ranking;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record RecommendationHistoryEvent(
        long reservationId,
        long storeId,
        Instant occurredAt,
        Set<Long> menuIds
) {

    public RecommendationHistoryEvent {
        if (reservationId <= 0 || storeId <= 0) {
            throw new IllegalArgumentException("history identifiers must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(menuIds, "menuIds are required");
        if (menuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("menuIds must be positive");
        }
        menuIds = Set.copyOf(menuIds);
    }
}
