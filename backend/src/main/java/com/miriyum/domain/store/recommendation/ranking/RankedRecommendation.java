package com.miriyum.domain.store.recommendation.ranking;

import java.util.Objects;

public record RankedRecommendation(
        RecommendationCandidate candidate,
        int intentScore,
        int historyScore,
        int totalScore,
        RecommendationReason reason
) {

    public RankedRecommendation {
        Objects.requireNonNull(candidate, "candidate is required");
        if (intentScore < 0
                || intentScore > 70
                || historyScore < 0
                || historyScore > 30
                || totalScore != intentScore + historyScore) {
            throw new IllegalArgumentException("invalid recommendation scores");
        }
    }
}
