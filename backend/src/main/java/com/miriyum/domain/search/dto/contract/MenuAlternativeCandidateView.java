package com.miriyum.domain.search.dto.contract;

import java.math.BigDecimal;
import java.util.List;

public record MenuAlternativeCandidateView(
        long storeId, String storeName, long menuId, String menuName, int unitPrice,
        String primaryCategoryCode, List<String> secondaryCategoryCodes,
        String allergenInformationStatus, List<MenuAlternativeAllergenView> allergens,
        BigDecimal latitude, BigDecimal longitude, int conceptScore
) {
    public MenuAlternativeCandidateView {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        allergens = List.copyOf(allergens);
        if (conceptScore < 0 || conceptScore > 50) {
            throw new IllegalArgumentException("conceptScore must be between 0 and 50");
        }
    }

    public MenuAlternativeCandidateView(
            long storeId, String storeName, long menuId, String menuName, int unitPrice,
            String primaryCategoryCode, List<String> secondaryCategoryCodes,
            String allergenInformationStatus, List<MenuAlternativeAllergenView> allergens,
            BigDecimal latitude, BigDecimal longitude
    ) {
        this(storeId, storeName, menuId, menuName, unitPrice, primaryCategoryCode,
                secondaryCategoryCodes, allergenInformationStatus, allergens,
                latitude, longitude, 0);
    }
}
