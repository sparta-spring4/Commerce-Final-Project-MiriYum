package com.miriyum.domain.alternative.model;

import java.math.BigDecimal;
import java.util.List;

public record EligibleAlternative(AlternativeMenuCandidate candidate,
        int secondaryCategoryMatchCount, int absolutePriceDifference,
        List<AlternativeReasonCode> reasonCodes) {
    public EligibleAlternative {
        reasonCodes = List.copyOf(reasonCodes);
    }

    public static EligibleAlternative testValue(long storeId, long menuId, int price,
            int matches, int difference, BigDecimal distance) {
        return new EligibleAlternative(new AlternativeMenuCandidate(storeId, "store", menuId,
                "menu", price, "MAIN", List.of(), "REGISTERED", List.of(), null, null,
                distance), matches, difference, List.of());
    }
}
