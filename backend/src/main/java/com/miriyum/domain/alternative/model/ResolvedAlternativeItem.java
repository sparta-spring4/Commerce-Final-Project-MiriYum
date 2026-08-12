package com.miriyum.domain.alternative.model;

import java.math.BigDecimal;
import java.util.List;

public record ResolvedAlternativeItem(long storeId, String storeName, long menuId,
        String menuName, int unitPrice, int availableOnlineQuantity,
        int secondaryCategoryMatchCount, int absolutePriceDifference, BigDecimal distanceMeters,
        BigDecimal latitude, BigDecimal longitude, List<AlternativeReasonCode> reasonCodes) {
    public ResolvedAlternativeItem { reasonCodes = List.copyOf(reasonCodes); }
}
