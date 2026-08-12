package com.miriyum.domain.recommendation.ranking;

import java.math.BigDecimal;

public record RecommendationCursorKey(
        int totalScore,
        boolean storeCategoryMatch,
        int menuSecondaryCategoryMatchCount,
        int availabilityRank,
        BigDecimal distanceMeters,
        long storeId
) {

    private static final String NULL_DISTANCE = "~";

    public RecommendationCursorKey {
        if (totalScore < 0
                || totalScore > 100
                || menuSecondaryCategoryMatchCount < 0
                || availabilityRank < 0
                || availabilityRank > 2
                || (distanceMeters != null && distanceMeters.signum() < 0)
                || storeId <= 0) {
            throw new IllegalArgumentException("invalid recommendation cursor key");
        }
        distanceMeters = distanceMeters == null
                ? null
                : distanceMeters.stripTrailingZeros();
    }

    public static RecommendationCursorKey from(RankedRecommendation result) {
        RecommendationCandidate candidate = result.candidate();
        return new RecommendationCursorKey(
                result.totalScore(),
                candidate.storeCategoryMatch(),
                candidate.menuSecondaryCategoryMatchCount(),
                candidate.availability().rank(),
                candidate.distanceMeters(),
                candidate.storeId());
    }

    public static RecommendationCursorKey parse(String value, long storeId) {
        if (value == null) {
            throw new IllegalArgumentException("cursor value is required");
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 5
                || !("true".equals(parts[1]) || "false".equals(parts[1]))) {
            throw new IllegalArgumentException("invalid recommendation cursor value");
        }
        try {
            BigDecimal distance = NULL_DISTANCE.equals(parts[4])
                    ? null
                    : new BigDecimal(parts[4]);
            return new RecommendationCursorKey(
                    Integer.parseInt(parts[0]),
                    Boolean.parseBoolean(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    distance,
                    storeId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid recommendation cursor value", exception);
        }
    }

    public String serialize() {
        return totalScore
                + "|" + storeCategoryMatch
                + "|" + menuSecondaryCategoryMatchCount
                + "|" + availabilityRank
                + "|" + (distanceMeters == null
                ? NULL_DISTANCE
                : distanceMeters.toPlainString());
    }

    public boolean isAfter(RankedRecommendation candidate) {
        return compare(from(candidate), this) > 0;
    }

    static int compare(RecommendationCursorKey left, RecommendationCursorKey right) {
        int compared = Integer.compare(right.totalScore, left.totalScore);
        if (compared != 0) {
            return compared;
        }
        compared = Boolean.compare(right.storeCategoryMatch, left.storeCategoryMatch);
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(
                right.menuSecondaryCategoryMatchCount,
                left.menuSecondaryCategoryMatchCount);
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(right.availabilityRank, left.availabilityRank);
        if (compared != 0) {
            return compared;
        }
        compared = compareDistance(left.distanceMeters, right.distanceMeters);
        return compared != 0 ? compared : Long.compare(left.storeId, right.storeId);
    }

    private static int compareDistance(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }
}
