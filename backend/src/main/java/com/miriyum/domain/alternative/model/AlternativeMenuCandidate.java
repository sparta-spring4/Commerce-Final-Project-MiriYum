package com.miriyum.domain.alternative.model;

import com.miriyum.domain.search.dto.contract.MenuAlternativeAllergenView;
import java.math.BigDecimal;
import java.util.List;

public record AlternativeMenuCandidate(long storeId, String storeName, long menuId,
        String menuName, int unitPrice, String primaryCategoryCode,
        List<String> secondaryCategoryCodes, String allergenInformationStatus,
        List<MenuAlternativeAllergenView> allergens, BigDecimal latitude,
        BigDecimal longitude, BigDecimal distanceMeters) {
    public AlternativeMenuCandidate {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        allergens = List.copyOf(allergens);
    }
}
