package com.miriyum.domain.recommendation.ranking;

import java.util.Objects;
import java.util.Set;

public record RecommendationCandidateSignals(
        boolean storeCategoryMatch,
        boolean menuPrimaryCategoryMatch,
        int menuSecondaryCategoryMatchCount,
        int tagMatchCount,
        Set<Long> currentMenuIds
) {

    public RecommendationCandidateSignals {
        if (menuSecondaryCategoryMatchCount < 0 || tagMatchCount < 0) {
            throw new IllegalArgumentException("recommendation signal counts must not be negative");
        }
        Objects.requireNonNull(currentMenuIds, "currentMenuIds are required");
        if (currentMenuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("currentMenuIds must be positive");
        }
        currentMenuIds = Set.copyOf(currentMenuIds);
    }

    public static RecommendationCandidateSignals empty() {
        return new RecommendationCandidateSignals(false, false, 0, 0, Set.of());
    }
}
