package com.miriyum.domain.store.recommendation.ranking;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

public record RecommendationCandidate(
        long storeId,
        int relevanceTier,
        boolean storeCategoryMatch,
        boolean menuPrimaryCategoryMatch,
        int menuSecondaryCategoryMatchCount,
        int tagMatchCount,
        RecommendationAvailability availability,
        BigDecimal distanceMeters,
        Set<Long> currentMenuIds
) {

    public RecommendationCandidate {
        if (storeId <= 0
                || relevanceTier < 0
                || relevanceTier > 4
                || menuSecondaryCategoryMatchCount < 0
                || tagMatchCount < 0
                || (distanceMeters != null && distanceMeters.signum() < 0)) {
            throw new IllegalArgumentException("invalid recommendation candidate");
        }
        Objects.requireNonNull(availability, "availability is required");
        Objects.requireNonNull(currentMenuIds, "currentMenuIds are required");
        if (currentMenuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("currentMenuIds must be positive");
        }
        currentMenuIds = Set.copyOf(currentMenuIds);
        distanceMeters = distanceMeters == null
                ? null
                : distanceMeters.stripTrailingZeros();
    }
}
