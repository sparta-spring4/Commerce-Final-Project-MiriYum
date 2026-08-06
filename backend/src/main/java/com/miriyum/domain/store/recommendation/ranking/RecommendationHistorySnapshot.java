package com.miriyum.domain.store.recommendation.ranking;

import java.util.List;
import java.util.Objects;

public record RecommendationHistorySnapshot(List<RecommendationHistoryEvent> events) {

    public RecommendationHistorySnapshot {
        Objects.requireNonNull(events, "events are required");
        if (events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("history events must not contain null");
        }
        events = List.copyOf(events);
    }

    public static RecommendationHistorySnapshot empty() {
        return new RecommendationHistorySnapshot(List.of());
    }
}
