package com.miriyum.domain.recommendation.ranking;

public enum RecommendationAvailability {
    AVAILABLE(2, 2),
    NOT_REQUESTED(1, 1),
    UNAVAILABLE(0, 0);

    private final int score;
    private final int rank;

    RecommendationAvailability(int score, int rank) {
        this.score = score;
        this.rank = rank;
    }

    public int score() {
        return score;
    }

    public int rank() {
        return rank;
    }
}
