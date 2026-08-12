package com.miriyum.domain.alternative.service;

import com.miriyum.domain.alternative.model.EligibleAlternative;
import java.util.Comparator;

public final class MenuAlternativeOrdering {
    private MenuAlternativeOrdering() {}

    public static Comparator<EligibleAlternative> sameStoreComparator() {
        return Comparator.comparingInt(EligibleAlternative::secondaryCategoryMatchCount).reversed()
                .thenComparingInt(EligibleAlternative::absolutePriceDifference)
                .thenComparingInt(value -> value.candidate().unitPrice())
                .thenComparingLong(value -> value.candidate().menuId());
    }

    public static Comparator<EligibleAlternative> nearbyStoreComparator() {
        return Comparator.<EligibleAlternative, java.math.BigDecimal>comparing(
                        value -> value.candidate().distanceMeters(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt(
                        EligibleAlternative::secondaryCategoryMatchCount).reversed())
                .thenComparingInt(EligibleAlternative::absolutePriceDifference)
                .thenComparingLong(value -> value.candidate().storeId())
                .thenComparingLong(value -> value.candidate().menuId());
    }
}
