package com.miriyum.domain.alternative.model;

import java.util.List;

public record AlternativeMenuSource(long storeId, long menuId, int unitPrice,
        String primaryCategoryCode, List<String> secondaryCategoryCodes) {
    public AlternativeMenuSource {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
    }
}
