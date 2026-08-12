package com.miriyum.domain.recommendation.ranking;

import java.math.BigDecimal;
import java.util.Objects;

public record RecommendationSearchCandidate(
        long storeId,
        int relevanceTier,
        RecommendationAvailability reservationAvailability,
        BigDecimal distanceMeters
) {

    public RecommendationSearchCandidate {
        if (storeId <= 0
                || relevanceTier < 0
                || relevanceTier > 4
                || (distanceMeters != null && distanceMeters.signum() < 0)) {
            throw new IllegalArgumentException("invalid recommendation search candidate");
        }
        Objects.requireNonNull(
                reservationAvailability,
                "reservationAvailability is required");
        distanceMeters = distanceMeters == null
                ? null
                : distanceMeters.stripTrailingZeros();
    }
}
