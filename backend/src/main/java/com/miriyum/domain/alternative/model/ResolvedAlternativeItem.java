package com.miriyum.domain.alternative.model;

import java.math.BigDecimal;
import java.util.List;

public record ResolvedAlternativeItem(long storeId, String storeName, long menuId,
        String menuName, int unitPrice, int availableOnlineQuantity,
        int secondaryCategoryMatchCount, int absolutePriceDifference, BigDecimal distanceMeters,
        BigDecimal latitude, BigDecimal longitude, List<AlternativeReasonCode> reasonCodes,
        MenuAlternativeScore score) {
    public ResolvedAlternativeItem {
        reasonCodes = List.copyOf(reasonCodes);
        java.util.Objects.requireNonNull(score, "score is required");
    }
}
