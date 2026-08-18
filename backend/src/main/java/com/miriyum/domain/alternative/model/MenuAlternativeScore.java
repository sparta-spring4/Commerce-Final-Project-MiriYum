package com.miriyum.domain.alternative.model;

public record MenuAlternativeScore(
        int conceptScore,
        int secondaryCategoryScore,
        int priceSimilarityScore,
        int totalScore,
        MenuAlternativeRankingReason rankingReason
) {
    public MenuAlternativeScore {
        if (conceptScore < 0 || conceptScore > 50
                || secondaryCategoryScore < 0 || secondaryCategoryScore > 20
                || priceSimilarityScore < 0 || priceSimilarityScore > 30
                || totalScore != conceptScore + secondaryCategoryScore + priceSimilarityScore
                || totalScore > 100) {
            throw new IllegalArgumentException("invalid alternative score");
        }
    }
}
